//! JNI bridge used by the native Android Activity. The Activity copies Storage Access Framework
//! documents into its private files directory; this crate then imports those durable paths into a
//! normal FilmCraft session.
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

use filmcraft_engine::{Session, autosave::AutosaveConfig};
use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jlong, jstring};
use serde_json::{Value, json};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock, PoisonError};

// Only JNI entry points may opt into the exported-symbol ABI annotation. This module contains no
// unsafe blocks or pointer manipulation; JNI values are handled through the safe jni crate API.
#[allow(unsafe_code)]
mod ffi {
    use super::*;

    static SESSION: OnceLock<Mutex<Session>> = OnceLock::new();
    static DATA_DIR: OnceLock<PathBuf> = OnceLock::new();

    fn session() -> &'static Mutex<Session> {
        SESSION.get_or_init(|| Mutex::new(Session::default()))
    }

    fn java_text(env: &mut JNIEnv<'_>, value: String) -> jstring {
        env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
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
    pub extern "system" fn Java_com_filmcraft_mobile_MainActivity_nativeInitialize(
        mut env: JNIEnv<'_>,
        _activity: JObject<'_>,
        data_dir: JString<'_>,
    ) -> jstring {
        let result = catch_unwind(AssertUnwindSafe(|| {
            let root = decode(&mut env, &data_dir)?;
            let data = PathBuf::from(root).join("filmcraft");
            std::fs::create_dir_all(&data).map_err(|e| format!("could not create app data folder: {e}"))?;
            let mut state = session().lock().unwrap_or_else(PoisonError::into_inner);
            let _ = DATA_DIR.set(data.clone());
            let draft = data.join("FilmCraftMobile.fcproj");
            if draft.is_file() {
                state.execute("file.open", json!({"path": draft.to_string_lossy()})).map_err(|e| format!("could not reopen mobile draft: {e}"))?;
            }
            if state.persistence.is_none() {
                state.start_autosave(AutosaveConfig::new(data)).map_err(|e| format!("could not start project recovery: {e}"))?;
            }
            Ok(json!({"project": state.project.name.clone(), "ready": true}))
        }))
        .unwrap_or_else(|_| Err("native session initialization failed; see the Android log".into()));
        java_text(&mut env, json_result(result))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_filmcraft_mobile_MainActivity_nativeImportMedia(
        mut env: JNIEnv<'_>,
        _activity: JObject<'_>,
        path: JString<'_>,
        overlay: jboolean,
    ) -> jstring {
        let result = catch_unwind(AssertUnwindSafe(|| {
            let path = decode(&mut env, &path)?;
            if path.is_empty() {
                return Err("media path is empty".into());
            }
            let mut state = session().lock().unwrap_or_else(PoisonError::into_inner);
            let imported = state.execute("file.import", json!({"paths": [path]})).map_err(|e| e.to_string())?;
            let timeline = imported.get("items").and_then(Value::as_array)
                .and_then(|items| items.first()).and_then(Value::as_u64)
                .map(|item| {
                    if overlay != 0 && state.active_sequence().is_none() {
                        Err(filmcraft_engine::EngineError::Other("add a main video or photo before placing an overlay".into()))
                    } else if overlay != 0 {
                        state.execute("timeline.place", json!({"item": item, "track": "new", "audio": false, "time": 0}))
                    } else if state.active_sequence().is_none() {
                        state.execute("file.newSequenceFromClip", json!({"items": [item]}))
                    } else {
                        let time = state.active_sequence().map(|sequence| sequence.duration().0).unwrap_or_default();
                        state.execute("timeline.place", json!({"item": item, "time": time}))
                    }
                });
            let save_path = state.path.clone().or_else(|| {
                DATA_DIR.get().map(|data| data.join("FilmCraftMobile.fcproj").to_string_lossy().into_owned())
            }).ok_or_else(|| "mobile app data folder has not been initialized".to_string())?;
            if state.path.is_some() {
                state.execute("file.save", json!({})).map_err(|e| format!("media imported, but the draft could not be saved: {e}"))?;
            } else {
                state.execute("file.saveAs", json!({"path": save_path})).map_err(|e| format!("media imported, but the draft could not be saved: {e}"))?;
            }
            let timeline = timeline.map(|result| match result {
                Ok(value) => json!({"ok": true, "result": value}),
                Err(error) => json!({"ok": false, "error": error.to_string()}),
            }).unwrap_or_else(|| json!({"ok": false, "error": "import produced no project item"}));
            Ok(json!({"import": imported, "timeline": timeline}))
        }))
        .unwrap_or_else(|_| Err("media import failed unexpectedly; the project session is still available".into()));
        java_text(&mut env, json_result(result))
    }
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_filmcraft_mobile_MainActivity_nativeListVideoEffects(
        mut env: JNIEnv<'_>,
        _activity: JObject<'_>,
    ) -> jstring {
        let result = catch_unwind(AssertUnwindSafe(|| {
            let mut state = session().lock().unwrap_or_else(PoisonError::into_inner);
            state.execute("effects.list", json!({"kind": "Video", "detail": false})).map_err(|e| e.to_string())
        }))
        .unwrap_or_else(|_| Err("could not list FilmCraft video effects".into()));
        java_text(&mut env, json_result(result))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_filmcraft_mobile_MainActivity_nativeApplyEffect(
        mut env: JNIEnv<'_>,
        _activity: JObject<'_>,
        clip_id: jlong,
        effect_id: JString<'_>,
    ) -> jstring {
        let result = catch_unwind(AssertUnwindSafe(|| {
            if clip_id <= 0 {
                return Err("select a timeline clip before applying an effect".into());
            }
            let effect = decode(&mut env, &effect_id)?;
            if effect.is_empty() {
                return Err("effect ID is empty".into());
            }
            let mut state = session().lock().unwrap_or_else(PoisonError::into_inner);
            let applied = state.execute("effects.apply", json!({"clips": [clip_id as u64], "effect": effect})).map_err(|e| e.to_string())?;
            let save_path = state.path.clone().or_else(|| {
                DATA_DIR.get().map(|data| data.join("FilmCraftMobile.fcproj").to_string_lossy().into_owned())
            }).ok_or_else(|| "mobile app data folder has not been initialized".to_string())?;
            if state.path.is_some() {
                state.execute("file.save", json!({})).map_err(|e| format!("effect applied, but the project could not be saved: {e}"))?;
            } else {
                state.execute("file.saveAs", json!({"path": save_path})).map_err(|e| format!("effect applied, but the project could not be saved: {e}"))?;
            }
            Ok(applied)
        }))
        .unwrap_or_else(|_| Err("effect could not be applied; the editing session remains available".into()));
        java_text(&mut env, json_result(result))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_filmcraft_mobile_MainActivity_nativeListCommands(
        mut env: JNIEnv<'_>,
        _activity: JObject<'_>,
    ) -> jstring {
        let result = catch_unwind(AssertUnwindSafe(|| {
            let commands = filmcraft_engine::commands::command_specs().iter().map(|spec| {
                json!({"id": spec.id, "label": spec.label, "params": spec.params, "journal": spec.journal})
            }).collect::<Vec<_>>();
            Ok(json!(commands))
        })).unwrap_or_else(|_| Err("could not list FilmCraft engine commands".into()));
        java_text(&mut env, json_result(result))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_filmcraft_mobile_MainActivity_nativeExecute(
        mut env: JNIEnv<'_>,
        _activity: JObject<'_>,
        command: JString<'_>,
        params: JString<'_>,
    ) -> jstring {
        let result = catch_unwind(AssertUnwindSafe(|| {
            let command = decode(&mut env, &command)?;
            let raw_params = decode(&mut env, &params)?;
            if command.is_empty() || command.len() > 160 {
                return Err("command ID is empty or too long".into());
            }
            if raw_params.len() > 1_048_576 {
                return Err("command parameters exceed the 1 MiB Android bridge limit".into());
            }
            let params: Value = serde_json::from_str(&raw_params).map_err(|error| format!("invalid command parameters: {error}"))?;
            if !params.is_object() {
                return Err("command parameters must be a JSON object".into());
            }
            let mut state = session().lock().unwrap_or_else(PoisonError::into_inner);
            let result = state.execute(&command, params).map_err(|error| error.to_string())?;
            if state.is_dirty() {
                let save_path = state.path.clone().or_else(|| DATA_DIR.get().map(|data| data.join("FilmCraftMobile.fcproj").to_string_lossy().into_owned()))
                    .ok_or_else(|| "mobile app data folder has not been initialized".to_string())?;
                if state.path.is_some() {
                    state.execute("file.save", json!({})).map_err(|error| format!("command succeeded, but the project could not be saved: {error}"))?;
                } else {
                    state.execute("file.saveAs", json!({"path": save_path})).map_err(|error| format!("command succeeded, but the project could not be saved: {error}"))?;
                }
            }
            Ok(result)
        })).unwrap_or_else(|_| Err("FilmCraft engine command failed unexpectedly; the session remains available".into()));
        java_text(&mut env, json_result(result))
    }

}
