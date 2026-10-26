//! Userspace command for SusFS priority UID filtering.

use anyhow::Result;

use crate::susfs::abi::consts::{
    CMD_SUSFS_SET_PRIORITY_UID_FILTER, ERR_CMD_NOT_SUPPORTED,
    SUSFS_PRIORITY_UID_FILTER_APP_STR_SIZE,
};
use crate::susfs::abi::{SusfsPriorityUidFilter, send};

fn alloc_priority_uid_filter() -> Box<SusfsPriorityUidFilter> {
    let mut payload = Box::<SusfsPriorityUidFilter>::new_uninit();
    // SAFETY: the allocation is correctly aligned and zero is valid for every
    // field (a byte array and an i32). Initialize in place to avoid a 64 KiB
    // temporary on the stack, including in unoptimized builds.
    unsafe {
        std::ptr::write_bytes(
            payload.as_mut_ptr().cast::<u8>(),
            0,
            std::mem::size_of::<SusfsPriorityUidFilter>(),
        );
        payload.assume_init()
    }
}

/// Replace the priority UID filter with a comma-separated package list.
///
/// The kernel resolves package names to Android application UIDs, validates
/// their supported range and atomically replaces the current filter.
pub fn set_priority_uid_filter(package_names: &str) -> Result<()> {
    let normalized = package_names
        .split(',')
        .map(str::trim)
        .filter(|name| !name.is_empty())
        .collect::<Vec<_>>()
        .join(",");
    // Upstream rejects an empty buffer but tolerates empty comma-separated
    // entries. A comma therefore replaces the bitmap with an empty bitmap.
    let normalized = if normalized.is_empty() {
        ",".to_string()
    } else {
        normalized
    };
    for name in normalized.split(',').filter(|name| !name.is_empty()) {
        anyhow::ensure!(
            name.len() <= 255
                && name != "."
                && name != ".."
                && name
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_'),
            "invalid package name: {name}"
        );
    }

    let bytes = normalized.as_bytes();
    if bytes.len() >= SUSFS_PRIORITY_UID_FILTER_APP_STR_SIZE {
        anyhow::bail!(
            "package list is too long ({} bytes, maximum {})",
            bytes.len(),
            SUSFS_PRIORITY_UID_FILTER_APP_STR_SIZE - 1
        );
    }

    let mut payload = alloc_priority_uid_filter();
    payload.err = ERR_CMD_NOT_SUPPORTED;
    payload.app_strings[..bytes.len()].copy_from_slice(bytes);

    send(
        CMD_SUSFS_SET_PRIORITY_UID_FILTER,
        payload.as_mut(),
        "set_priority_uid_filter",
    )?;
    anyhow::ensure!(
        payload.err != ERR_CMD_NOT_SUPPORTED,
        "priority UID filter is not supported by the running SuSFS kernel"
    );
    Ok(())
}
