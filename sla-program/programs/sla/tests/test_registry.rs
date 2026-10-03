//! `initialize_config`, `register_monitor`, `set_monitor_active`.
mod common;

use common::*;
use sla::{error::SlaError, state::Config};
use solana_signer::Signer;

#[test]
fn initialize_config_stores_params_and_admin() {
    let env = Env::new();
    let config: Config = env.read(&config_pda());
    assert_eq!(config.admin, env.admin.pubkey());
    assert_eq!(config.window_secs, WINDOW);
    assert_eq!(config.report_grace_secs, GRACE);
    assert_eq!(config.max_monitors_per_sla, 5);
}

#[test]
fn initialize_config_rejects_out_of_range_params() {
    for (w, g, m) in [(9, 30, 5), (86_401, 30, 5), (100, 0, 5), (100, 86_401, 5), (100, 30, 0), (100, 30, 6)] {
        let mut env = Env::bare();
        let admin = env.admin.insecure_clone();
        assert_err(env.initialize_config(&admin, w, g, m), SlaError::InvalidConfig);
        assert!(!env.exists(&config_pda()));
    }
}

#[test]
fn initialize_config_runs_once() {
    let mut env = Env::new();
    let other = env.funded();
    assert!(env.initialize_config(&other, WINDOW, GRACE, 5).is_err());
    assert_eq!(env.read::<Config>(&config_pda()).admin, env.admin.pubkey());
}

#[test]
fn register_monitor_creates_active_monitor() {
    let mut env = Env::new();
    let node = env.funded();
    let admin = env.admin.insecure_clone();
    assert_ok(env.register_monitor_as(&admin, &node.pubkey(), "eu-west"));
    let m = env.monitor(&node.pubkey());
    assert_eq!(m.authority, node.pubkey());
    assert_eq!(m.name, "eu-west");
    assert!(m.active);
    assert_eq!((m.reports_submitted, m.slots_voted, m.slots_agreed), (0, 0, 0));
}

#[test]
fn register_monitor_requires_admin() {
    let mut env = Env::new();
    let intruder = env.funded();
    let node = env.funded();
    assert_err(env.register_monitor_as(&intruder, &node.pubkey(), "x"), SlaError::Unauthorized);
}

#[test]
fn register_monitor_validates_name() {
    let mut env = Env::new();
    let node = env.funded();
    let admin = env.admin.insecure_clone();
    assert_err(env.register_monitor_as(&admin, &node.pubkey(), ""), SlaError::InvalidMonitorName);
    assert_err(env.register_monitor_as(&admin, &node.pubkey(), &"n".repeat(33)), SlaError::InvalidMonitorName);
    assert_ok(env.register_monitor_as(&admin, &node.pubkey(), &"n".repeat(32)));
}

#[test]
fn register_monitor_twice_fails() {
    let mut env = Env::new();
    let node = env.new_monitor();
    let admin = env.admin.insecure_clone();
    assert!(env.register_monitor_as(&admin, &node.pubkey(), "again").is_err());
}

#[test]
fn set_monitor_active_toggles() {
    let mut env = Env::new();
    let node = env.new_monitor();
    let admin = env.admin.insecure_clone();
    assert_ok(env.set_monitor_active_as(&admin, &node.pubkey(), false));
    assert!(!env.monitor(&node.pubkey()).active);
    assert_ok(env.set_monitor_active_as(&admin, &node.pubkey(), true));
    assert!(env.monitor(&node.pubkey()).active);
}

#[test]
fn set_monitor_active_requires_admin() {
    let mut env = Env::new();
    let node = env.new_monitor();
    let intruder = env.funded();
    assert_err(env.set_monitor_active_as(&intruder, &node.pubkey(), false), SlaError::Unauthorized);
    assert!(env.monitor(&node.pubkey()).active);
}
