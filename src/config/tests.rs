use super::*;
use jj_lib::config::ConfigLayer;

const JJ_TEST_DEFAULTS: &str = r#"
    [user]
    name = "Test User"
    email = "test@example.com"

    [operation]
    hostname = "test-host"
    username = "test-user"

    [signing]
    behavior = "drop"
"#;

/// Create isolated test settings with only jjuicy.toml defaults, no user config.
pub fn settings_with_gg_defaults() -> UserSettings {
    let mut config = StackedConfig::empty();
    config.add_layer(ConfigLayer::parse(ConfigSource::Default, JJ_TEST_DEFAULTS).unwrap());
    config.add_layer(
        ConfigLayer::parse(ConfigSource::Default, include_str!("../config/jjuicy.toml")).unwrap(),
    );
    UserSettings::from_config(config).unwrap()
}

/// Create test settings with custom TOML layered on top of defaults.
fn settings_with_overrides(toml: &str) -> UserSettings {
    let mut config = StackedConfig::empty();
    config.add_layer(ConfigLayer::parse(ConfigSource::Default, JJ_TEST_DEFAULTS).unwrap());
    config.add_layer(
        ConfigLayer::parse(ConfigSource::Default, include_str!("../config/jjuicy.toml")).unwrap(),
    );
    config.add_layer(ConfigLayer::parse(ConfigSource::User, toml).unwrap());
    UserSettings::from_config(config).unwrap()
}

/// Create test settings with jjuicy.* config override extraction applied.
fn settings_with_extracted_overrides(toml: &str) -> UserSettings {
    let mut config = StackedConfig::empty();
    config.add_layer(ConfigLayer::parse(ConfigSource::Default, JJ_TEST_DEFAULTS).unwrap());
    config.add_layer(
        ConfigLayer::parse(ConfigSource::Default, include_str!("../config/jjuicy.toml")).unwrap(),
    );
    config.add_layer(ConfigLayer::parse(ConfigSource::User, toml).unwrap());
    if let Some(overrides) = extract_overrides(&config) {
        config.add_layer(overrides);
    }
    UserSettings::from_config(config).unwrap()
}

#[test]
fn track_recent_workspaces_default_is_true() {
    let settings = settings_with_gg_defaults();
    assert!(settings.ui_track_recent_workspaces());
}

#[test]
fn track_recent_workspaces_can_be_disabled() {
    let settings = settings_with_overrides(
        r#"
            [jjuicy.ui]
            track-recent-workspaces = false
            "#,
    );
    assert!(!settings.ui_track_recent_workspaces());
}

#[test]
fn mark_unpushed_bookmarks_default_is_true() {
    let settings = settings_with_gg_defaults();
    assert!(settings.ui_mark_unpushed_bookmarks());
}

#[test]
fn mark_unpushed_bookmarks_can_be_disabled() {
    let settings = settings_with_overrides(
        r#"
            [jjuicy.ui]
            mark-unpushed-bookmarks = false
            "#,
    );
    assert!(!settings.ui_mark_unpushed_bookmarks());
}

#[test]
fn mark_unpushed_bookmarks_legacy_name_fallback() {
    // without jjuicy.toml to test the fallback behavior
    let mut config = StackedConfig::empty();
    config.add_layer(
        ConfigLayer::parse(
            ConfigSource::Default,
            &format!("{JJ_TEST_DEFAULTS}\n[jjuicy.ui]\nmark-unpushed-branches = false"),
        )
        .unwrap(),
    );
    let settings = UserSettings::from_config(config).unwrap();
    assert!(!settings.ui_mark_unpushed_bookmarks());
}

#[test]
fn mark_unpushed_bookmarks_new_name_takes_precedence() {
    let settings = settings_with_overrides(
        r#"
            [jjuicy.ui]
            mark-unpushed-branches = false
            mark-unpushed-bookmarks = true
            "#,
    );
    assert!(settings.ui_mark_unpushed_bookmarks());
}

#[test]
fn log_page_size_default() {
    let settings = settings_with_gg_defaults();
    assert_eq!(settings.query_log_page_size(), 1000);
}

#[test]
fn log_page_size_can_be_overridden() {
    let settings = settings_with_overrides(
        r#"
            [jjuicy.queries]
            log-page-size = 500
            "#,
    );
    assert_eq!(settings.query_log_page_size(), 500);
}

#[test]
fn large_repo_heuristic_default() {
    let settings = settings_with_gg_defaults();
    assert_eq!(settings.query_large_repo_heuristic(), 100000);
}

#[test]
fn auto_snapshot_default_is_none() {
    let settings = settings_with_gg_defaults();
    assert!(settings.query_auto_snapshot().is_none());
}

#[test]
fn auto_snapshot_can_be_enabled() {
    let settings = settings_with_overrides(
        r#"
            [jjuicy.queries]
            auto-snapshot = true
            "#,
    );
    assert_eq!(settings.query_auto_snapshot(), Some(true));
}

#[test]
fn theme_override_default_is_none() {
    let settings = settings_with_gg_defaults();
    assert!(settings.ui_theme_override().is_none());
}

#[test]
fn theme_override_can_be_set() {
    let settings = settings_with_overrides(
        r#"
            [jjuicy.ui]
            theme-override = "dark"
            "#,
    );
    assert_eq!(settings.ui_theme_override(), Some("dark".to_string()));
}

#[test]
fn recent_workspaces_default_is_empty() {
    let settings = settings_with_gg_defaults();
    assert!(settings.ui_recent_workspaces().is_empty());
}

#[test]
fn recent_workspaces_returns_configured_paths() {
    let settings = settings_with_overrides(
        r#"
            [jjuicy.ui]
            recent-workspaces = ["/path/one", "/path/two"]
            "#,
    );
    assert_eq!(
        settings.ui_recent_workspaces(),
        vec!["/path/one".to_string(), "/path/two".to_string()]
    );
}

mod extract_overrides {
    use super::super::{JjuicySettings, extract_overrides, native_keys};
    use super::{JJ_TEST_DEFAULTS, settings_with_extracted_overrides};
    use jj_lib::config::{ConfigLayer, ConfigSource, StackedConfig};
    use jj_lib::settings::UserSettings;

    #[test]
    fn override_basic_user_name() {
        let settings = settings_with_extracted_overrides(
            r#"
            [jjuicy.user]
            name = "GG User"
            "#,
        );
        assert_eq!(settings.user_name(), "GG User");
    }

    #[test]
    fn override_beats_regular_setting() {
        let settings = settings_with_extracted_overrides(
            r#"
            [user]
            name = "CLI User"
            [jjuicy.user]
            name = "GG User"
            "#,
        );
        assert_eq!(settings.user_name(), "GG User");
    }

    #[test]
    fn override_with_dotted_key_syntax() {
        let settings = settings_with_extracted_overrides(
            r#"
            jjuicy.user.name = "Dotted GG"
            [user]
            name = "CLI User"
            "#,
        );
        assert_eq!(settings.user_name(), "Dotted GG");
    }

    #[test]
    fn override_nested_table() {
        let settings = settings_with_extracted_overrides(
            r#"
            [jjuicy.snapshot]
            auto-track = "glob:src/**"
            "#,
        );
        assert_eq!(
            settings.get_string("snapshot.auto-track").unwrap(),
            "glob:src/**"
        );
    }

    #[test]
    fn override_multiple_keys() {
        let settings = settings_with_extracted_overrides(
            r#"
            [jjuicy.user]
            name = "GG User"
            email = "gg@example.com"
            [jjuicy.signing]
            behavior = "own"
            "#,
        );
        assert_eq!(settings.user_name(), "GG User");
        assert_eq!(settings.user_email(), "gg@example.com");
        assert_eq!(settings.get_string("signing.behavior").unwrap(), "own");
    }

    #[test]
    fn native_gg_keys_are_not_overrides() {
        let settings = settings_with_extracted_overrides(
            r#"
            [jjuicy.queries]
            log-page-size = 42
            "#,
        );
        // jjuicy.queries.log-page-size is a native jjuicy key, not an override for "queries.log-page-size"
        assert_eq!(settings.query_log_page_size(), 42);
        assert!(settings.get_string("queries.log-page-size").is_err());
    }

    #[test]
    fn no_overrides_is_noop() {
        let settings = settings_with_extracted_overrides(
            r#"
            [user]
            name = "Plain User"
            "#,
        );
        assert_eq!(settings.user_name(), "Plain User");
    }

    #[test]
    fn override_from_higher_layer_wins() {
        // user-level jjuicy.user.name vs repo-level jjuicy.user.name: repo wins
        let mut config = StackedConfig::empty();
        config.add_layer(ConfigLayer::parse(ConfigSource::Default, JJ_TEST_DEFAULTS).unwrap());
        config.add_layer(
            ConfigLayer::parse(ConfigSource::Default, include_str!("../config/jjuicy.toml"))
                .unwrap(),
        );
        config.add_layer(
            ConfigLayer::parse(ConfigSource::User, r#"jjuicy.user.name = "User Level""#).unwrap(),
        );
        config.add_layer(
            ConfigLayer::parse(ConfigSource::Repo, r#"jjuicy.user.name = "Repo Level""#).unwrap(),
        );
        if let Some(overrides) = extract_overrides(&config) {
            config.add_layer(overrides);
        }
        let settings = UserSettings::from_config(config).unwrap();
        assert_eq!(settings.user_name(), "Repo Level");
    }

    #[test]
    fn override_beats_repo_level_regular_setting() {
        // user-level jjuicy.user.name should beat repo-level user.name
        let mut config = StackedConfig::empty();
        config.add_layer(ConfigLayer::parse(ConfigSource::Default, JJ_TEST_DEFAULTS).unwrap());
        config.add_layer(
            ConfigLayer::parse(ConfigSource::Default, include_str!("../config/jjuicy.toml"))
                .unwrap(),
        );
        config.add_layer(
            ConfigLayer::parse(ConfigSource::User, r#"jjuicy.user.name = "GG User""#).unwrap(),
        );
        config.add_layer(
            ConfigLayer::parse(ConfigSource::Repo, r#"user.name = "Repo CLI User""#).unwrap(),
        );
        if let Some(overrides) = extract_overrides(&config) {
            config.add_layer(overrides);
        }
        let settings = UserSettings::from_config(config).unwrap();
        assert_eq!(settings.user_name(), "GG User");
    }

    #[test]
    fn native_keys_discovered_from_gg_toml() {
        let keys = native_keys();
        // leaf keys from jjuicy.toml
        assert!(keys.contains("default-mode"));
        assert!(keys.contains("queries.log-page-size"));
        assert!(keys.contains("queries.large-repo-heuristic"));
        assert!(keys.contains("ui.recent-workspaces"));
        assert!(keys.contains("ui.track-recent-workspaces"));
        assert!(keys.contains("ui.mark-unpushed-bookmarks"));
        assert!(keys.contains("ui.theme-override"));
        assert!(keys.contains("web.default-port"));
        assert!(keys.contains("web.client-timeout"));
        assert!(keys.contains("web.launch-browser"));
        // supplementary keys not in jjuicy.toml
        assert!(keys.contains("queries.auto-snapshot"));
        assert!(keys.contains("ui.mark-unpushed-branches"));
        // table prefixes should NOT be in the set (only leaf keys)
        assert!(!keys.contains("queries"));
        assert!(!keys.contains("ui"));
        assert!(!keys.contains("web"));
    }

    #[test]
    fn mixed_ui_table_native_and_override() {
        // jjuicy.ui.theme-override is native, jjuicy.ui.diff-editor is a jj override
        let settings = settings_with_extracted_overrides(
            r#"
            [jjuicy.ui]
            theme-override = "dark"
            diff-editor = "meld"
            "#,
        );
        // native key stays under jjuicy.ui, not injected as ui.theme-override
        assert_eq!(settings.ui_theme_override(), Some("dark".to_string()));
        assert!(settings.get_string("ui.theme-override").is_err());
        // override key is injected as ui.diff-editor
        assert_eq!(settings.get_string("ui.diff-editor").unwrap(), "meld");
    }

    #[test]
    fn preset_is_native() {
        // custom presets are jjuicy-native, not jj overrides
        let settings = settings_with_extracted_overrides(
            r#"
            [jjuicy.presets]
            my-custom-preset = "bookmarks()"
            "#,
        );
        assert!(settings.get_string("presets.my-custom-preset").is_err());
        assert_eq!(
            settings
                .get_string("jjuicy.presets.my-custom-preset")
                .unwrap(),
            "bookmarks()"
        );
    }

    #[test]
    fn revset_override_is_forwarded() {
        // jjuicy.revsets.* keys are pure jj overrides now that presets live elsewhere
        let settings = settings_with_extracted_overrides(
            r#"
            [jjuicy.revsets]
            short-prefixes = "trunk()"
            log = "all()"
            "#,
        );
        assert_eq!(
            settings.get_string("revsets.short-prefixes").unwrap(),
            "trunk()"
        );
        assert_eq!(settings.get_string("revsets.log").unwrap(), "all()");
    }

    #[test]
    fn preset_and_revset_override_coexist() {
        // presets stay native, revset overrides get forwarded
        let settings = settings_with_extracted_overrides(
            r#"
            [jjuicy.presets]
            my-preset = "trunk()..@"

            [jjuicy.revsets]
            short-prefixes = "trunk()"
            "#,
        );
        // my-preset is native — not injected
        assert!(settings.get_string("presets.my-preset").is_err());
        assert_eq!(
            settings.get_string("jjuicy.presets.my-preset").unwrap(),
            "trunk()..@"
        );
        // short-prefixes is a jj override — injected
        assert_eq!(
            settings.get_string("revsets.short-prefixes").unwrap(),
            "trunk()"
        );
    }

    #[test]
    fn deep_nesting_override() {
        let settings = settings_with_extracted_overrides(
            r#"
            [jjuicy.signing.backends.gpg]
            program = "/usr/bin/gpg2"
            "#,
        );
        assert_eq!(
            settings.get_string("signing.backends.gpg.program").unwrap(),
            "/usr/bin/gpg2"
        );
    }

    #[test]
    fn cross_layer_merge_preserves_siblings() {
        // user layer sets jjuicy.signing.behavior, repo layer sets jjuicy.signing.backend
        // both should end up in the override doc
        let mut config = StackedConfig::empty();
        config.add_layer(ConfigLayer::parse(ConfigSource::Default, JJ_TEST_DEFAULTS).unwrap());
        config.add_layer(
            ConfigLayer::parse(ConfigSource::Default, include_str!("../config/jjuicy.toml"))
                .unwrap(),
        );
        config.add_layer(
            ConfigLayer::parse(ConfigSource::User, r#"jjuicy.signing.behavior = "own""#).unwrap(),
        );
        config.add_layer(
            ConfigLayer::parse(ConfigSource::Repo, r#"jjuicy.signing.backend = "gpg""#).unwrap(),
        );
        if let Some(overrides) = extract_overrides(&config) {
            config.add_layer(overrides);
        }
        let settings = UserSettings::from_config(config).unwrap();
        assert_eq!(settings.get_string("signing.behavior").unwrap(), "own");
        assert_eq!(settings.get_string("signing.backend").unwrap(), "gpg");
    }

    #[test]
    fn auto_snapshot_not_injected_as_override() {
        // jjuicy.queries.auto-snapshot is native (supplementary list), not a jj override
        let settings = settings_with_extracted_overrides(
            r#"
            [jjuicy.queries]
            auto-snapshot = true
            "#,
        );
        assert_eq!(settings.query_auto_snapshot(), Some(true));
        assert!(settings.get_string("queries.auto-snapshot").is_err());
    }
}
