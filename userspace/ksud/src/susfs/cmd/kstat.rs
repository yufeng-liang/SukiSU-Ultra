//! SuSFS `sus_kstat` operations.
//!
//! Three of the commands here (`add_sus_kstat`, `update_sus_kstat`,
//! `update_sus_kstat_full_clone`) share a common path-resolution + stat +
//! syscall pipeline, factored into [`fill_kstat_from_path`] and
//! [`fill_and_send_kstat`].

use std::str::FromStr;

use anyhow::{Context, Result};

use crate::susfs::abi::consts::{
    CMD_SUSFS_ADD_SUS_KSTAT, CMD_SUSFS_ADD_SUS_KSTAT_STATICALLY, CMD_SUSFS_UPDATE_SUS_KSTAT,
    ERR_CMD_NOT_SUPPORTED, KSTAT_AUTO_SPOOF, KSTAT_AUTO_SPOOF_FULL_CLONE,
    KSTAT_SPOOF_ATIME_TV_NSEC, KSTAT_SPOOF_ATIME_TV_SEC, KSTAT_SPOOF_BLKSIZE, KSTAT_SPOOF_BLOCKS,
    KSTAT_SPOOF_CTIME_TV_NSEC, KSTAT_SPOOF_CTIME_TV_SEC, KSTAT_SPOOF_DEV, KSTAT_SPOOF_INO,
    KSTAT_SPOOF_MTIME_TV_NSEC, KSTAT_SPOOF_MTIME_TV_SEC, KSTAT_SPOOF_NLINK, KSTAT_SPOOF_SIZE,
    SUSFS_MAX_PATHNAME,
};
use crate::susfs::abi::{SusfsKstat, send};
use crate::susfs::util::{canonicalize, copy_metadata_into_kstat, copy_path_into};

/// Resolve `path`, `stat(2)` it, and fill a [`SusfsKstat`] in place.
///
/// `is_statically` is left at `0` (the callers below set it to `1` for the
/// `*_statically` variant); `flags` is set to the value passed in and
/// `err` is initialised to [`ERR_CMD_NOT_SUPPORTED`] so an unsupported
/// kernel surfaces as `Ok(())` rather than a confusing error.
fn fill_kstat_from_path(path: &str, flags: u32) -> Result<SusfsKstat> {
    let resolved = canonicalize(path)?;
    let resolved_str = resolved
        .to_str()
        .ok_or_else(|| anyhow::anyhow!("resolved path is not valid utf-8"))?;

    let md = std::fs::metadata(&resolved)
        .with_context(|| format!("failed to get stat from path: '{resolved_str}'"))?;

    let mut kstat = SusfsKstat {
        is_statically: 0,
        target_ino: 0,
        target_pathname: [0; SUSFS_MAX_PATHNAME],
        spoofed_ino: 0,
        spoofed_dev: 0,
        spoofed_nlink: 0,
        spoofed_size: 0,
        spoofed_atime_tv_sec: 0,
        spoofed_atime_tv_nsec: 0,
        spoofed_mtime_tv_sec: 0,
        spoofed_mtime_tv_nsec: 0,
        spoofed_ctime_tv_sec: 0,
        spoofed_ctime_tv_nsec: 0,
        spoofed_blocks: 0,
        spoofed_blksize: 0,
        flags,
        err: ERR_CMD_NOT_SUPPORTED,
    };
    copy_path_into(&mut kstat.target_pathname, resolved_str)?;
    copy_metadata_into_kstat(&mut kstat, &md);
    Ok(kstat)
}

/// Convenience wrapper: fill + syscall + error reporting.
fn fill_and_send_kstat(path: &str, cmd: u32, flags: u32, op_name: &str) -> Result<()> {
    let mut kstat = fill_kstat_from_path(path, flags)?;
    send(cmd, &mut kstat, op_name)
}

// ── dynamic (auto-spoof) kstat commands ──────────────────────────────────────

/// Begin tracking `path` for later kstat spoofing; must be called *before*
/// the path is bind-mounted or overlayed so the kernel can capture the
/// original stat info.
pub fn add_sus_kstat(path: &str) -> Result<()> {
    fill_and_send_kstat(
        path,
        CMD_SUSFS_ADD_SUS_KSTAT,
        KSTAT_AUTO_SPOOF,
        "add_sus_kstat",
    )
}

/// Complete the spoofing started by [`add_sus_kstat`]. Updates `target_ino`
/// but keeps `size` and `blocks` as the current stat.
pub fn update_sus_kstat(path: &str) -> Result<()> {
    fill_and_send_kstat(
        path,
        CMD_SUSFS_UPDATE_SUS_KSTAT,
        KSTAT_AUTO_SPOOF,
        "update_sus_kstat",
    )
}

/// Like [`update_sus_kstat`] but also spoofs `nlink` and `size` — the
/// resulting stat looks identical to the original.
pub fn update_sus_kstat_full_clone(path: &str) -> Result<()> {
    fill_and_send_kstat(
        path,
        CMD_SUSFS_UPDATE_SUS_KSTAT,
        KSTAT_AUTO_SPOOF_FULL_CLONE,
        "update_sus_kstat_full_clone",
    )
}

// ── static (caller-supplied stat values) ─────────────────────────────────────

/// Parse a caller-provided stat value. `default` means retain the value
/// read from the current file metadata.
fn parse_stat_override<T>(name: &str, value: &str) -> Result<Option<T>>
where
    T: FromStr,
    T::Err: std::fmt::Display,
{
    if value == "default" {
        return Ok(None);
    }
    value.parse::<T>().map(Some).map_err(|error| {
        anyhow::anyhow!(
            "invalid {name} value '{value}': expected an integer or 'default' ({error})"
        )
    })
}

/// Add a static kstat entry. Fields set to `default` retain their current
/// stat value; only explicitly supplied fields are enabled in the kernel
/// spoofing mask.
#[allow(clippy::similar_names, clippy::too_many_arguments)]
pub fn add_sus_kstat_statically(
    path: &str,
    ino: &str,
    dev: &str,
    nlink: &str,
    size: &str,
    atime_sec: &str,
    atime_nsec: &str,
    mtime_sec: &str,
    mtime_nsec: &str,
    ctime_sec: &str,
    ctime_nsec: &str,
    blocks: &str,
    blksize: &str,
) -> Result<()> {
    let mut cmd = fill_kstat_from_path(path, 0)?;
    cmd.is_statically = 1;

    macro_rules! apply_override {
        ($field:ident, $value:expr, $ty:ty, $flag:ident) => {
            if let Some(value) = parse_stat_override::<$ty>(stringify!($field), $value)? {
                cmd.$field = value;
                cmd.flags |= $flag;
            }
        };
    }

    apply_override!(spoofed_ino, ino, u64, KSTAT_SPOOF_INO);
    apply_override!(spoofed_dev, dev, u64, KSTAT_SPOOF_DEV);
    apply_override!(spoofed_nlink, nlink, u32, KSTAT_SPOOF_NLINK);
    apply_override!(spoofed_size, size, u64, KSTAT_SPOOF_SIZE);
    apply_override!(
        spoofed_atime_tv_sec,
        atime_sec,
        i64,
        KSTAT_SPOOF_ATIME_TV_SEC
    );
    apply_override!(
        spoofed_atime_tv_nsec,
        atime_nsec,
        u64,
        KSTAT_SPOOF_ATIME_TV_NSEC
    );
    apply_override!(
        spoofed_mtime_tv_sec,
        mtime_sec,
        i64,
        KSTAT_SPOOF_MTIME_TV_SEC
    );
    apply_override!(
        spoofed_mtime_tv_nsec,
        mtime_nsec,
        u64,
        KSTAT_SPOOF_MTIME_TV_NSEC
    );
    apply_override!(
        spoofed_ctime_tv_sec,
        ctime_sec,
        i64,
        KSTAT_SPOOF_CTIME_TV_SEC
    );
    apply_override!(
        spoofed_ctime_tv_nsec,
        ctime_nsec,
        u64,
        KSTAT_SPOOF_CTIME_TV_NSEC
    );
    apply_override!(spoofed_blocks, blocks, u64, KSTAT_SPOOF_BLOCKS);
    apply_override!(spoofed_blksize, blksize, i64, KSTAT_SPOOF_BLKSIZE);

    send(
        CMD_SUSFS_ADD_SUS_KSTAT_STATICALLY,
        &mut cmd,
        "add_sus_kstat_statically",
    )
}
