/*
 * KPM (Kernel Patch Module) operations.
 *
 * Reached via the new single-extension ioctl KSU_IOCTL_EXTENSION with
 * extension_id=KSU_EXTENSION_KPM. The user payload is a struct ksu_kpm_cmd
 * forwarded verbatim to kernel/extension/kpm.c::do_kpm().
 */
use std::{
    ffi::{CString, OsStr},
    fs, io,
    os::unix::ffi::OsStrExt,
    os::unix::fs::PermissionsExt,
    path::Path,
};

use anyhow::{Context, Result, bail};

use crate::ksu_uapi;
use crate::ksucalls::kpm_io as kpm_ioctl;

const KPM_DIR: &str = "/data/adb/kpm";
const KPM_TEXT_BUF_SIZE: usize = 1024;
const KPM_INFO_BUF_SIZE: usize = 256;

fn kpm_io(control_code: u32, arg1: u64, arg2: u64) -> Result<i32> {
    let mut ret = -1i32;
    let mut cmd = ksu_uapi::ksu_kpm_cmd {
        control_code: u64::from(control_code),
        arg1,
        arg2,
        result_code: &raw mut ret as u64,
    };
    let result = unsafe { kpm_ioctl(&raw mut cmd) }
        .with_context(|| format!("KPM ioctl {control_code} failed"))?;
    if result < 0 {
        bail!(
            "KPM ioctl {control_code} failed: {}",
            io::Error::from_raw_os_error(-result)
        );
    }
    Ok(ret)
}

fn check_result(operation: &str, ret: i32) -> Result<()> {
    if ret < 0 {
        bail!("{operation}: {}", io::Error::from_raw_os_error(-ret));
    }
    Ok(())
}

fn read_text(control_code: u32, capacity: usize, operation: &str) -> Result<String> {
    let mut buf = vec![0u8; capacity];
    let ret = kpm_io(control_code, buf.as_mut_ptr() as u64, buf.len() as u64)?;
    check_result(operation, ret)?;
    Ok(buf2str(&buf))
}

fn cstring(value: &OsStr, description: &str) -> Result<CString> {
    CString::new(value.as_bytes())
        .with_context(|| format!("{description} contains an embedded NUL byte"))
}

fn load_module_result<P>(path: P, args: Option<&str>, event: Option<&str>) -> Result<i32>
where
    P: AsRef<Path>,
{
    let path = path.as_ref();
    let path_c = cstring(path.as_os_str(), "KPM path")?;
    let args = args.map_or_else(|| CString::new(String::new()), CString::new)?;
    let event_c = event.map(CString::new).transpose()?;
    let event_cmd = event_c
        .as_ref()
        .map(|event_c| ksu_uapi::ksu_kpm_load_event_cmd {
            args: args.as_ptr() as u64,
            event: event_c.as_ptr() as u64,
        });
    let control_code = if event_cmd.is_some() {
        ksu_uapi::SUKISU_KPM_LOAD_EVENT
    } else {
        ksu_uapi::SUKISU_KPM_LOAD
    };
    let payload_ptr = event_cmd
        .as_ref()
        .map_or(args.as_ptr() as u64, |cmd| std::ptr::from_ref(cmd) as u64);

    kpm_io(control_code, path_c.as_ptr() as u64, payload_ptr)
}

pub fn load_module<P>(path: P, args: Option<&str>) -> Result<()>
where
    P: AsRef<Path>,
{
    let path = path.as_ref();
    let ret = load_module_result(path, args, None)?;
    check_result(
        &format!("Failed to load KPM module {}", path.display()),
        ret,
    )
}

fn load_module_at_event<P>(path: P, args: Option<&str>, event: &str) -> Result<()>
where
    P: AsRef<Path>,
{
    let path = path.as_ref();
    let mut ret = load_module_result(path, args, Some(event))?;
    if ret == -libc::EOPNOTSUPP {
        log::warn!(
            "KPM bridge does not support event-aware loading; falling back to the default load event"
        );
        ret = load_module_result(path, args, None)?;
    }
    check_result(
        &format!(
            "Failed to load KPM module {} at event {event}",
            path.display()
        ),
        ret,
    )
}

pub fn list() -> Result<()> {
    println!(
        "{}",
        read_text(
            ksu_uapi::SUKISU_KPM_LIST,
            KPM_TEXT_BUF_SIZE,
            "Failed to get KPM list",
        )?
    );
    Ok(())
}

pub fn unload_module(name: &str) -> Result<()> {
    let name_c = cstring(OsStr::new(name), "KPM module name")?;

    let ret = kpm_io(ksu_uapi::SUKISU_KPM_UNLOAD, name_c.as_ptr() as u64, 0)?;
    check_result(&format!("Failed to unload KPM module {name}"), ret)
}

pub fn info(name: &str) -> Result<()> {
    let name_c = cstring(OsStr::new(name), "KPM module name")?;
    let mut buf = vec![0u8; KPM_INFO_BUF_SIZE];

    let ret = kpm_io(
        ksu_uapi::SUKISU_KPM_INFO,
        name_c.as_ptr() as u64,
        buf.as_mut_ptr() as u64,
    )?;
    check_result(&format!("Failed to get KPM info for {name}"), ret)?;
    println!("{}", buf2str(&buf));
    Ok(())
}

pub fn control(name: &str, args: String) -> Result<i32> {
    let name_c = cstring(OsStr::new(name), "KPM module name")?;
    let args_c = CString::new(args)?;

    let ret = kpm_io(
        ksu_uapi::SUKISU_KPM_CONTROL,
        name_c.as_ptr() as u64,
        args_c.as_ptr() as u64,
    )?;
    check_result(&format!("Failed to control KPM module {name}"), ret)?;
    Ok(ret)
}

pub fn num() -> Result<i32> {
    let ret = kpm_io(ksu_uapi::SUKISU_KPM_NUM, 0, 0)?;
    check_result("Failed to get KPM module count", ret)?;
    println!("{ret}");
    Ok(ret)
}

pub fn version() -> Result<()> {
    let version = read_text(
        ksu_uapi::SUKISU_KPM_VERSION,
        KPM_TEXT_BUF_SIZE,
        "Failed to get KPM version",
    )?;
    print!("{}", version.trim());
    Ok(())
}

pub fn check_version() -> Result<String> {
    let binding = read_text(
        ksu_uapi::SUKISU_KPM_VERSION,
        KPM_TEXT_BUF_SIZE,
        "KPM version ioctl failed",
    )?;
    let ver = binding.trim();
    if ver.is_empty() {
        bail!("KPM version response is empty");
    }
    log::info!("KPM: version check ok: {ver}");
    Ok(ver.to_string())
}

fn ensure_dir() -> Result<()> {
    let dir = Path::new(KPM_DIR);

    fs::create_dir_all(dir).with_context(|| format!("Failed to create {}", dir.display()))?;
    let metadata = fs::symlink_metadata(dir)
        .with_context(|| format!("Failed to inspect {}", dir.display()))?;
    if !metadata.is_dir() {
        bail!("KPM path {} is not a directory", dir.display());
    }

    if metadata.permissions().mode() & 0o7777 != 0o777 {
        fs::set_permissions(dir, fs::Permissions::from_mode(0o777))
            .with_context(|| format!("Failed to set permissions on {}", dir.display()))?;
    }

    Ok(())
}

pub fn booted_load() -> Result<()> {
    check_version()?;

    if crate::utils::is_safe_mode() {
        log::warn!("KPM: safe-mode – all modules won't load");
        return Ok(());
    }

    ensure_dir()?;
    load_all_modules()?;

    Ok(())
}

fn load_all_modules() -> Result<()> {
    let dir = Path::new(KPM_DIR);

    if !dir.is_dir() {
        return Ok(());
    }

    let mut modules = Vec::new();
    for entry in dir.read_dir()? {
        let entry = entry?;
        if !entry.file_type()?.is_file() {
            continue;
        }
        let path = entry.path();
        if path.extension().is_some_and(|ext| ext == OsStr::new("kpm")) {
            modules.push(path);
        }
    }
    modules.sort_unstable();

    let mut failures = Vec::new();
    for path in modules {
        if let Err(error) = load_module_at_event(&path, None, "post-fs-data") {
            log::error!("KPM: failed to load {}: {error:#}", path.display());
            failures.push(format!("{}: {error}", path.display()));
        }
    }
    if !failures.is_empty() {
        bail!(
            "failed to load {} KPM module(s):\n{}",
            failures.len(),
            failures.join("\n")
        );
    }
    Ok(())
}

/// Convert zero-padded kernel buffer to owned String.
/// DON'T REMOVE!!! we must use this method, because kernel use \0 to end of buffer
/// if directly to_string_lossy, we will get a lot of uninit data
/// refer: res = copy_to_user(arg1, &buffer, len + 1);
fn buf2str(buf: &[u8]) -> String {
    // Bound the scan even if an older kernel returns an unterminated buffer.
    let end = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    String::from_utf8_lossy(&buf[..end]).into_owned()
}
