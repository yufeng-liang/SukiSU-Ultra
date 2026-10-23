//! Read-only SuSFS queries.
//!
//! These never modify kernel state and only probe what the running kernel
//! supports / reports.

use crate::susfs::abi::consts::{
    CMD_SUSFS_SHOW_ENABLED_FEATURES, CMD_SUSFS_SHOW_VARIANT, CMD_SUSFS_SHOW_VERSION,
    ERR_CMD_NOT_SUPPORTED, SUSFS_ENABLED_FEATURES_SIZE, SUSFS_MAX_VARIANT_BUFSIZE,
    SUSFS_MAX_VERSION_BUFSIZE,
};
use crate::susfs::abi::{SusfsFeatures, SusfsVariant, SusfsVersion, send};
use crate::susfs::util::cstr_buf_to_string;

/// Return the SuSFS version reported by the kernel, or the literal string
/// `"unsupport"` when the feature is missing or the version is malformed.
pub fn get_susfs_version() -> String {
    let mut cmd = SusfsVersion {
        susfs_version: [0; SUSFS_MAX_VERSION_BUFSIZE],
        err: ERR_CMD_NOT_SUPPORTED,
    };
    let _ = send(CMD_SUSFS_SHOW_VERSION, &mut cmd, "show_version");

    let ver = cstr_buf_to_string(&cmd.susfs_version);
    if ver.starts_with('v') {
        ver
    } else {
        "unsupport".to_string()
    }
}

/// `true` when the kernel reports a SuSFS version (i.e. the module is loaded).
pub fn get_susfs_status() -> bool {
    get_susfs_version() != "unsupport"
}

/// Return the comma-separated list of SuSFS features enabled in the kernel.
pub fn get_susfs_features() -> String {
    let mut cmd = SusfsFeatures {
        enabled_features: [0; SUSFS_ENABLED_FEATURES_SIZE],
        err: ERR_CMD_NOT_SUPPORTED,
    };
    let _ = send(
        CMD_SUSFS_SHOW_ENABLED_FEATURES,
        &mut cmd,
        "show_enabled_features",
    );
    cstr_buf_to_string(&cmd.enabled_features)
}

/// Return the SuSFS variant string (e.g. `"gki"` / `"non-gki"`).
pub fn get_susfs_variant() -> String {
    let mut cmd = SusfsVariant {
        susfs_variant: [0; SUSFS_MAX_VARIANT_BUFSIZE],
        err: ERR_CMD_NOT_SUPPORTED,
    };
    let _ = send(CMD_SUSFS_SHOW_VARIANT, &mut cmd, "show_variant");
    cstr_buf_to_string(&cmd.susfs_variant)
}

/// Identify the implementation from the kernel ABI, not visible module names.
/// susfs4ksu-lkm reports GKI-LKM and SUSFS_LKM_MODULES. For an available
/// SuSFS implementation, default to built-in when neither LKM marker is present.
pub fn get_susfs_implementation() -> &'static str {
    if !get_susfs_status() {
        return "unsupported";
    }
    implementation_from_info(&get_susfs_variant(), &get_susfs_features())
}

fn implementation_from_info(variant: &str, features: &str) -> &'static str {
    let variant = variant.trim().to_ascii_uppercase().replace('_', "-");
    if variant.ends_with("-LKM")
        || features
            .lines()
            .any(|line| line.trim() == "SUSFS_LKM_MODULES")
    {
        return "lkm";
    }
    "builtin"
}
