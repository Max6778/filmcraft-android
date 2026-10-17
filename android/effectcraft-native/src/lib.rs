//! Small JNI boundary for the independent EffectCraft composition engine. The Android frontend
//! sends the same JSON commands as EffectCraft's other frontends; project state stays in Rust.
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

use effectcraft_engine::{Session, commands};
use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::{jbyteArray, jstring};
use serde_json::{Value, json};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock, PoisonError};

#[allow(unsafe_code)]
mod ffi {
    use super::*;

    static SESSION: OnceLock<Mutex<Session>> = OnceLock::new();
    static PROJECT_PATH: OnceLock<PathBuf> = OnceLock::new();
    static MOBILE_RAYON_POOL: OnceLock<()> = OnceLock::new();

    fn session() -> &'static Mutex<Session> {
        SESSION.get_or_init(|| Mutex::new(Session::default()))
    }

    fn java_text(env: &mut JNIEnv<'_>, value: String) -> jstring {
        env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    fn java_bytes(env: &mut JNIEnv<'_>, value: Vec<u8>) -> jbyteArray {
        env.byte_array_from_slice(&value).map(|array| array.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    fn decode(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String, String> {
        env.get_string(value).map(|s| s.to_string_lossy().into_owned()).map_err(|e| format!("invalid Android string: {e}"))
    }

    fn json_result(result: Result<Value, String>) -> String {
        match result {
            Ok(value) => json!({"ok": true, "result": value}).to_string(),
            Err(error) => json!({"ok": false, "error": error}).to_string(),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_filmcraft_mobile_MainActivity_nativeEffectCraftInitialize(
        mut env: JNIEnv<'_>,
        _activity: JObject<'_>,
        data_dir: JString<'_>,
    ) -> jstring {
        let result = catch_unwind(AssertUnwindSafe(|| {
            let root = PathBuf::from(decode(&mut env, &data_dir)?).join("effectcraft");
            std::fs::create_dir_all(&root).map_err(|e| format!("could not create EffectCraft project folder: {e}"))?;
            let _ = MOBILE_RAYON_POOL.get_or_init(|| {
                let _ = rayon::ThreadPoolBuilder::new().num_threads(2).build_global();
            });
            let project = root.join("Mobile.ecproj");
            let mut state = session().lock().unwrap_or_else(PoisonError::into_inner);
            let _ = PROJECT_PATH.set(project.clone());
            if project.is_file() {
                state.execute("file.open", json!({"path": project.to_string_lossy()})).map_err(|e| format!("could not reopen EffectCraft project: {e}"))?;
            } else {
                state.execute("comp.new", json!({"name": "Mobile Composition", "width": 1080, "height": 1920, "frameRate": 30, "duration": 10.0})).map_err(|e| format!("could not create EffectCraft composition: {e}"))?;
                state.execute("file.saveAs", json!({"path": project.to_string_lossy()})).map_err(|e| format!("could not save EffectCraft project: {e}"))?;
            }
            Ok(json!({"ready": true, "compositionEngine": "EffectCraft", "path": project.to_string_lossy()}))
        }))
        .unwrap_or_else(|_| Err("EffectCraft initialization failed; the FilmCraft session remains available".into()));
        java_text(&mut env, json_result(result))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_filmcraft_mobile_MainActivity_nativeEffectCraftListCommands(
        mut env: JNIEnv<'_>,
        _activity: JObject<'_>,
    ) -> jstring {
        let result = catch_unwind(AssertUnwindSafe(|| {
            let commands = commands::command_specs().iter().map(|spec| {
                json!({"id": spec.id, "label": spec.label, "params": spec.params, "journal": spec.journal})
            }).collect::<Vec<_>>();
            Ok(json!(commands))
        })).unwrap_or_else(|_| Err("could not list EffectCraft operations".into()));
        java_text(&mut env, json_result(result))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_filmcraft_mobile_MainActivity_nativeEffectCraftListEffects(
        mut env: JNIEnv<'_>,
        _activity: JObject<'_>,
    ) -> jstring {
        let result = catch_unwind(AssertUnwindSafe(|| {
            let effects = effectcraft_engine::effects::registry().iter().map(|effect| {
                json!({"id": effect.id, "name": effect.name, "category": effect.category, "gpu": effect.gpu, "float": effect.float,
                    "params": effect.params.iter().map(|param| json!({"id": param.id, "name": param.name,
                        "default": serde_json::to_value(&param.default).unwrap_or(Value::Null)})).collect::<Vec<_>>()})
            }).collect::<Vec<_>>();
            Ok(json!(effects))
        })).unwrap_or_else(|_| Err("could not list EffectCraft effects".into()));
        java_text(&mut env, json_result(result))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_filmcraft_mobile_MainActivity_nativeEffectCraftRender(
        mut env: JNIEnv<'_>,
        _activity: JObject<'_>,
    ) -> jbyteArray {
        let result = catch_unwind(AssertUnwindSafe(|| {
            let state = session().lock().unwrap_or_else(PoisonError::into_inner);
            let comp = state.active_comp_id().ok_or_else(|| "EffectCraft has no active composition".to_string())?;
            let (width, height, rgba) = state.render_rgba8(comp, effectcraft_engine::time::Tick::ZERO, 720)
                .map_err(|e| format!("EffectCraft preview render failed: {e}"))?;
            let byte_count = usize::try_from(width).ok().and_then(|w| usize::try_from(height).ok().and_then(|h| w.checked_mul(h)))
                .and_then(|pixels| pixels.checked_mul(4)).ok_or_else(|| "EffectCraft preview dimensions overflow".to_string())?;
            if rgba.len() != byte_count {
                return Err("EffectCraft returned an invalid preview buffer".to_string());
            }
            let mut output = Vec::with_capacity(byte_count.saturating_add(8));
            output.extend_from_slice(&width.to_be_bytes());
            output.extend_from_slice(&height.to_be_bytes());
            output.extend_from_slice(&rgba);
            Ok(output)
        })).unwrap_or_else(|_| Err("EffectCraft preview render failed unexpectedly".into()));
        match result {
            Ok(bytes) => java_bytes(&mut env, bytes),
            Err(error) => {
                let _ = env.throw_new("java/lang/IllegalStateException", error);
                std::ptr::null_mut()
            }
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_filmcraft_mobile_MainActivity_nativeEffectCraftExecute(
        mut env: JNIEnv<'_>,
        _activity: JObject<'_>,
        command: JString<'_>,
        params: JString<'_>,
    ) -> jstring {
        let result = catch_unwind(AssertUnwindSafe(|| {
            let command = decode(&mut env, &command)?;
            let raw_params = decode(&mut env, &params)?;
            if command.is_empty() || command.len() > 160 {
                return Err("EffectCraft command ID is empty or too long".into());
            }
            if raw_params.len() > 1_048_576 {
                return Err("EffectCraft parameters exceed the 1 MiB Android bridge limit".into());
            }
            let params: Value = serde_json::from_str(&raw_params).map_err(|e| format!("invalid EffectCraft command JSON: {e}"))?;
            if !params.is_object() {
                return Err("EffectCraft command parameters must be a JSON object".into());
            }
            let mut state = session().lock().unwrap_or_else(PoisonError::into_inner);
            let spec = effectcraft_engine::find_command(&command).ok_or_else(|| format!("unknown EffectCraft command `{command}`"))?;
            let changed = spec.journal;
            let value = state.execute(&command, params).map_err(|e| format!("{command}: {e}"))?;
            if changed {
                if state.path.is_some() {
                    state.execute("file.save", json!({})).map_err(|e| format!("{command} completed but EffectCraft could not save: {e}"))?;
                } else if let Some(path) = PROJECT_PATH.get() {
                    state.execute("file.saveAs", json!({"path": path.to_string_lossy()})).map_err(|e| format!("{command} completed but EffectCraft could not save: {e}"))?;
                }
            }
            Ok(value)
        })).unwrap_or_else(|_| Err("EffectCraft command failed unexpectedly; the project session is still available".into()));
        java_text(&mut env, json_result(result))
    }
}
