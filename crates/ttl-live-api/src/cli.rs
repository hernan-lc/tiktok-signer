//! Minimal CLI for the connection broker binary.
//!
//! Hand-rolled on `std::env::args` so local debugging (`--bundle ./webmssdk.js --bind ...`)
//! works without a new argument-parsing dependency. Precedence is explicit flags, then
//! environment variables, then built-in defaults:
//!
//! ```text
//! ttl-live-api [BUNDLE] [--bundle <PATH>] [--bind <ADDR>] [--api-keys <LIST>]
//! ```

use std::ffi::OsString;
use std::path::PathBuf;

use crate::config::parse_api_keys;
use crate::AppConfig;

pub const HELP: &str = "\
ttl-live-api — TikTok LIVE connection-ticket broker

USAGE:
    ttl-live-api [OPTIONS] [BUNDLE]

ARGS:
    [BUNDLE]            Signing bundle path (default: ./webmssdk.js).
                        Relative paths resolve against the current working
                        directory. Same as --bundle.

OPTIONS:
    --bundle <PATH>     Signing bundle (overrides SIGNING_BUNDLE / TTL_BUNDLE).
    --bind <ADDR>       Listen address, e.g. 127.0.0.1:18081
                        (default 0.0.0.0:8080, overrides HTTP_BIND).
    --api-keys <LIST>   Comma-separated secret=customer pairs (overrides API_KEYS).
    -h, --help          Print this help and exit.
";

/// Parsed flags. Everything is optional: unset flags fall back to env, then defaults.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct CliArgs {
    pub bundle: Option<PathBuf>,
    pub bind: Option<String>,
    pub api_keys: Option<String>,
}

/// How argument parsing asks the binary to stop before doing any work.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CliExit {
    Help,
    Error(String),
}

impl CliArgs {
    /// Parse the real process arguments.
    pub fn parse() -> Result<Self, CliExit> {
        Self::parse_from(std::env::args_os())
    }

    fn parse_from<I>(args: I) -> Result<Self, CliExit>
    where
        I: IntoIterator<Item = OsString>,
    {
        let mut parsed = Self::default();
        let mut positional_only = false;
        let mut pending: Option<&'static str> = None;

        let mut items = args.into_iter();
        // Skip argv[0] (the binary name); a missing argv[0] means no flags either.
        items.next();

        for item in items {
            if let Some(flag) = pending.take() {
                set_value(&mut parsed, flag, item)?;
                continue;
            }
            let text = item.to_string_lossy().into_owned();
            if !positional_only && text.starts_with('-') {
                if text == "--" {
                    positional_only = true;
                    continue;
                }
                if let Some((flag, value)) = text.split_once('=') {
                    match known_flag(flag) {
                        Some(flag) => set_str(&mut parsed, flag, value.to_owned())?,
                        None => return Err(CliExit::Error(unknown(&text))),
                    }
                    continue;
                }
                match text.as_str() {
                    "-h" | "--help" => return Err(CliExit::Help),
                    flag if known_flag(flag).is_some() => {
                        pending = known_flag(flag);
                    }
                    _ => return Err(CliExit::Error(unknown(&text))),
                }
                continue;
            }
            if parsed.bundle.is_some() {
                return Err(CliExit::Error(format!("unexpected extra argument: {text}")));
            }
            parsed.bundle = Some(PathBuf::from(text));
        }

        if let Some(flag) = pending {
            return Err(CliExit::Error(format!("{flag} needs a value")));
        }
        Ok(parsed)
    }

    /// Overlay explicit flags onto an env-loaded config. Unset flags keep env/defaults.
    pub fn apply(&self, mut config: AppConfig) -> AppConfig {
        if let Some(bind) = self.bind.as_deref() {
            if !bind.trim().is_empty() {
                config.bind = bind.to_owned();
            }
        }
        if let Some(raw) = self.api_keys.as_deref() {
            config.api_keys = parse_api_keys(Some(raw));
        }
        config
    }

    /// Bundle path with CLI > `SIGNING_BUNDLE`/`TTL_BUNDLE` > `./webmssdk.js` precedence.
    /// Relative paths are resolved against the current working directory so error messages
    /// and startup logs always show the location that was actually attempted.
    pub fn bundle_path(&self) -> PathBuf {
        let raw = self
            .bundle
            .clone()
            .or_else(|| std::env::var_os("SIGNING_BUNDLE").map(PathBuf::from))
            .or_else(|| std::env::var_os("TTL_BUNDLE").map(PathBuf::from))
            .unwrap_or_else(|| PathBuf::from("webmssdk.js"));
        absolutize(raw)
    }
}

fn known_flag(text: &str) -> Option<&'static str> {
    match text {
        "--bundle" | "--signing-bundle" => Some("--bundle"),
        "--bind" => Some("--bind"),
        "--api-keys" => Some("--api-keys"),
        _ => None,
    }
}

fn unknown(text: &str) -> String {
    format!("unknown flag: {text} (see --help)")
}

fn set_value(parsed: &mut CliArgs, flag: &'static str, value: OsString) -> Result<(), CliExit> {
    let value = value.to_string_lossy().into_owned();
    if value.trim().is_empty() {
        return Err(CliExit::Error(format!("{flag} needs a non-empty value")));
    }
    set_str(parsed, flag, value)
}

fn set_str(parsed: &mut CliArgs, flag: &'static str, value: String) -> Result<(), CliExit> {
    if value.trim().is_empty() {
        return Err(CliExit::Error(format!("{flag} needs a non-empty value")));
    }
    match flag {
        "--bundle" => parsed.bundle = Some(PathBuf::from(value)),
        "--bind" => parsed.bind = Some(value),
        "--api-keys" => parsed.api_keys = Some(value),
        _ => return Err(CliExit::Error(unknown(flag))),
    }
    Ok(())
}

/// Join a relative path onto the current working directory; absolute paths pass through.
/// The join is normalized lexically (no filesystem access), so `./x` displays as `<cwd>/x`.
pub fn absolutize(path: PathBuf) -> PathBuf {
    if path.is_absolute() {
        return path;
    }
    std::env::current_dir()
        .map(|cwd| cwd.join(&path).components().collect())
        .unwrap_or(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(words: &[&str]) -> Vec<OsString> {
        words.iter().map(OsString::from).collect()
    }

    #[test]
    fn positional_bundle_and_flags_parse() {
        let parsed = CliArgs::parse_from(args(&[
            "ttl-live-api",
            "./webmssdk.js",
            "--bind",
            "127.0.0.1:18081",
        ]))
        .unwrap();
        assert_eq!(parsed.bundle, Some(PathBuf::from("./webmssdk.js")));
        assert_eq!(parsed.bind.as_deref(), Some("127.0.0.1:18081"));
        assert_eq!(parsed.api_keys, None);
    }

    #[test]
    fn equals_form_and_long_alias_parse() {
        let parsed = CliArgs::parse_from(args(&[
            "ttl-live-api",
            "--signing-bundle=/tmp/webmssdk.js",
            "--api-keys=secret=acme",
        ]))
        .unwrap();
        assert_eq!(parsed.bundle, Some(PathBuf::from("/tmp/webmssdk.js")));
        assert_eq!(parsed.api_keys.as_deref(), Some("secret=acme"));
    }

    #[test]
    fn help_short_circuits() {
        assert_eq!(CliArgs::parse_from(args(&["ttl-live-api", "--help"])), Err(CliExit::Help));
        assert_eq!(CliArgs::parse_from(args(&["ttl-live-api", "-h"])), Err(CliExit::Help));
    }

    #[test]
    fn bad_input_is_an_error_not_a_panic() {
        assert!(matches!(
            CliArgs::parse_from(args(&["ttl-live-api", "--nope"])),
            Err(CliExit::Error(_))
        ));
        assert!(matches!(
            CliArgs::parse_from(args(&["ttl-live-api", "--bind"])),
            Err(CliExit::Error(_))
        ));
        assert!(matches!(
            CliArgs::parse_from(args(&["ttl-live-api", "a.js", "b.js"])),
            Err(CliExit::Error(_))
        ));
        assert!(matches!(
            CliArgs::parse_from(args(&["ttl-live-api", "--bind="])),
            Err(CliExit::Error(_))
        ));
    }

    #[test]
    fn flags_override_env_loaded_config() {
        let config = CliArgs {
            bind: Some("127.0.0.1:18081".into()),
            api_keys: Some("secret=acme".into()),
            bundle: None,
        }
        .apply(AppConfig::default());
        assert_eq!(config.bind, "127.0.0.1:18081");
        assert_eq!(config.api_keys.get("secret"), Some(&"acme".to_string()));

        let kept = CliArgs::default().apply(AppConfig::default());
        assert_eq!(kept.bind, AppConfig::default().bind);
        assert!(kept.api_keys.is_empty());
    }

    #[test]
    fn bundle_precedence_is_cli_then_env_then_default() {
        let saved_signing = std::env::var_os("SIGNING_BUNDLE");
        let saved_ttl = std::env::var_os("TTL_BUNDLE");
        std::env::remove_var("SIGNING_BUNDLE");
        std::env::remove_var("TTL_BUNDLE");
        let restore = || {
            restore_var("SIGNING_BUNDLE", saved_signing.clone());
            restore_var("TTL_BUNDLE", saved_ttl.clone());
        };

        let outcome = std::panic::catch_unwind(|| {
            // Default when neither CLI nor env is set.
            assert_eq!(
                CliArgs::default().bundle_path(),
                absolutize(PathBuf::from("webmssdk.js"))
            );
            // Env fills the gap.
            let env_bundle = std::env::temp_dir().join("env-bundle.js");
            std::env::set_var("TTL_BUNDLE", &env_bundle);
            assert_eq!(CliArgs::default().bundle_path(), env_bundle);
            // An explicit flag wins over env.
            let cli = CliArgs {
                bundle: Some(PathBuf::from("./cli-bundle.js")),
                ..CliArgs::default()
            };
            assert_eq!(cli.bundle_path(), absolutize(PathBuf::from("./cli-bundle.js")));
        });
        restore();
        assert!(outcome.is_ok());
    }

    fn restore_var(name: &str, value: Option<OsString>) {
        match value {
            Some(value) => std::env::set_var(name, value),
            None => std::env::remove_var(name),
        }
    }

    #[test]
    fn relative_paths_resolve_against_the_working_directory() {
        let resolved = absolutize(PathBuf::from("./webmssdk.js"));
        assert!(resolved.is_absolute());
        assert!(resolved.ends_with("webmssdk.js"));
        // temp_dir() is absolute on every platform; absolute paths pass through untouched.
        let absolute = std::env::temp_dir().join("webmssdk.js");
        assert!(absolute.is_absolute());
        assert_eq!(absolutize(absolute.clone()), absolute);
    }
}
