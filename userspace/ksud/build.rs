use std::env;
use std::ffi::OsString;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Command;

const BOOTSTRAP_SOURCE: &str = "src/lkm_image_bootstrap.S";
const BOOTSTRAP_OBJECT: &str = "lkm_image_bootstrap.o";
const PREPARED_BOOTSTRAP_OBJECT: &str = ".lkm_image_bootstrap.o";

fn get_git_version() -> Result<(u32, String), std::io::Error> {
    // CI 里 ksud 在独立工作副本/容器中编译，`git rev-list --count HEAD` 可能数到
    // 另一个 git 状态（浅历史、tag 快照），从而算出与管理器、LKM 都不一致的版本号。
    // 上游 workflow 已统一算好 version_code 并注入，优先采信它。
    let injected_code = env::var("KSU_VERSION_CODE")
        .ok()
        .and_then(|value| value.trim().parse::<u32>().ok())
        .filter(|code| *code > 0);

    // 版本名只是展示串，git 不可用时退化为空，不影响版本号本身。
    let version_name = Command::new("git")
        .args(["describe", "--tags", "--always"])
        .output()
        .map(|output| {
            String::from_utf8_lossy(&output.stdout)
                .trim()
                .trim_start_matches('v')
                .to_string()
        })
        .unwrap_or_default();

    if let Some(code) = injected_code {
        return Ok((code, version_name));
    }

    let output = Command::new("git")
        .args(["rev-list", "--count", "HEAD"])
        .output()?;

    let version_code = String::from_utf8(output.stdout)
        .map_err(|_| std::io::Error::other("Failed to read git count stdout"))?;
    let version_code: u32 = version_code
        .trim()
        .parse()
        .map_err(|_| std::io::Error::other("Failed to parse git count"))?;
    // For historical reasons
    Ok((40000 - 2815 + version_code, version_name))
}

fn configure_bindgen() {
    // The bindgen::Builder is the main entry point
    // to bindgen, and lets you build up options for
    // the resulting bindings.
    let mut builder = bindgen::Builder::default()
        // The input header we would like to generate
        // bindings for.
        .header("src/ksu_uapi.h")
        .clang_args(["-x", "c++", "-I../../"])
        // Tell cargo to invalidate the built crate whenever any of the
        // included header files changed.
        .parse_callbacks(Box::new(bindgen::CargoCallbacks::new()));
    if env::var("CARGO_CFG_TARGET_ARCH").as_deref() == Ok("riscv64") {
        // libc does not yet expose Android's RISC-V signal context. Generate
        // it from the target NDK rather than assuming another libc's layout.
        builder = builder.header_contents("ksu_signal_context.h", "#include <sys/ucontext.h>");
    }
    let bindings = builder
        // Finish the builder and generate the bindings.
        .generate()
        // Unwrap the Result and panic on failure.
        .expect("Unable to generate bindings");

    // Write the bindings to the $OUT_DIR/bindings.rs file.
    let out_path = std::path::PathBuf::from(env::var("OUT_DIR").unwrap());
    // for debug, uncomment below
    // let out_path = std::path::PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    bindings
        .write_to_file(out_path.join("bindings.rs"))
        .expect("Couldn't write bindings!");
}

fn validate_bootstrap_object(path: &Path) -> io::Result<()> {
    let object = fs::read(path)?;
    let valid = object.len() >= 64
        && object.starts_with(b"\x7fELF")
        && object[4] == 2
        && object[5] == 1
        && u16::from_le_bytes([object[16], object[17]]) == 1
        && u16::from_le_bytes([object[18], object[19]]) == 183;
    if valid {
        Ok(())
    } else {
        Err(io::Error::other(
            "bootstrap object must be a little-endian AArch64 ELF64 ET_REL",
        ))
    }
}

fn copy_bootstrap_object(source: &Path, output: &Path) -> io::Result<()> {
    validate_bootstrap_object(source)?;
    fs::copy(source, output)?;
    Ok(())
}

fn ndk_clang() -> Option<PathBuf> {
    let ndk = env::var_os("ANDROID_NDK_HOME")
        .or_else(|| env::var_os("ANDROID_NDK_ROOT"))
        .map(PathBuf::from)?;
    let prebuilt = ndk.join("toolchains/llvm/prebuilt");
    let mut hosts = fs::read_dir(prebuilt)
        .ok()?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .collect::<Vec<_>>();
    hosts.sort();
    hosts.into_iter().find_map(|host| {
        ["clang", "clang.exe"]
            .into_iter()
            .map(|name| host.join("bin").join(name))
            .find(|path| path.is_file())
    })
}

fn run_assembler(program: &Path, arguments: &[OsString]) -> Result<(), String> {
    let output = Command::new(program)
        .args(arguments)
        .output()
        .map_err(|error| format!("{}: {error}", program.display()))?;
    if output.status.success() {
        return Ok(());
    }
    let details = if output.stderr.is_empty() {
        &output.stdout
    } else {
        &output.stderr
    };
    Err(format!(
        "{}: {}",
        program.display(),
        String::from_utf8_lossy(details).trim()
    ))
}

fn assemble_bootstrap() {
    println!("cargo:rerun-if-changed={BOOTSTRAP_SOURCE}");
    println!("cargo:rerun-if-env-changed=KSU_LKM_BOOTSTRAP_OBJECT");
    println!("cargo:rerun-if-env-changed=KSU_LKM_BOOTSTRAP_CC");
    println!("cargo:rerun-if-env-changed=ANDROID_NDK_HOME");
    println!("cargo:rerun-if-env-changed=ANDROID_NDK_ROOT");

    let manifest = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap());
    let source = manifest.join(BOOTSTRAP_SOURCE);
    let output = PathBuf::from(env::var_os("OUT_DIR").unwrap()).join(BOOTSTRAP_OBJECT);

    if let Some(prebuilt) = env::var_os("KSU_LKM_BOOTSTRAP_OBJECT") {
        let prebuilt = PathBuf::from(prebuilt);
        copy_bootstrap_object(&prebuilt, &output).unwrap_or_else(|error| {
            panic!(
                "cannot use KSU_LKM_BOOTSTRAP_OBJECT {}: {error}",
                prebuilt.display()
            )
        });
        return;
    }

    // cross builds prepare this file on the host before entering the container.
    let prepared = manifest.join(PREPARED_BOOTSTRAP_OBJECT);
    println!("cargo:rerun-if-changed={}", prepared.display());
    if prepared.is_file() {
        copy_bootstrap_object(&prepared, &output).unwrap_or_else(|error| {
            panic!(
                "cannot use prepared bootstrap object {}: {error}",
                prepared.display()
            )
        });
        return;
    }

    let mut errors = Vec::new();
    let mut drivers = Vec::<PathBuf>::new();
    if let Some(compiler) = env::var_os("KSU_LKM_BOOTSTRAP_CC") {
        drivers.push(PathBuf::from(compiler));
    }
    drivers.push(PathBuf::from("aarch64-linux-gnu-gcc"));
    if let Some(clang) = ndk_clang() {
        drivers.push(clang);
    }
    drivers.push(PathBuf::from("clang"));

    for driver in drivers {
        let mut arguments = Vec::<OsString>::new();
        if driver
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.contains("clang"))
        {
            arguments.push("--target=aarch64-linux-gnu".into());
        }
        arguments.extend([
            "-c".into(),
            "-nostdlib".into(),
            "-o".into(),
            output.as_os_str().to_owned(),
            source.as_os_str().to_owned(),
        ]);
        match run_assembler(&driver, &arguments) {
            Ok(()) => {
                validate_bootstrap_object(&output)
                    .expect("assembler produced an invalid bootstrap object");
                return;
            }
            Err(error) => errors.push(error),
        }
    }

    let llvm_mc = PathBuf::from("llvm-mc");
    let llvm_arguments = [
        "-triple=aarch64-linux-gnu".into(),
        "-filetype=obj".into(),
        "-o".into(),
        output.as_os_str().to_owned(),
        source.as_os_str().to_owned(),
    ];
    match run_assembler(&llvm_mc, &llvm_arguments) {
        Ok(()) => {
            validate_bootstrap_object(&output)
                .expect("llvm-mc produced an invalid bootstrap object");
        }
        Err(error) => {
            errors.push(error);
            panic!(
                "cannot assemble the AArch64 LKM bootstrap; install an AArch64 GNU compiler, clang, or llvm-mc, or set KSU_LKM_BOOTSTRAP_OBJECT:\n{}",
                errors.join("\n")
            );
        }
    }
}

/// 包名单一真源，相对于 `CARGO_MANIFEST_DIR`（= userspace/ksud）。
///
/// 这里不写默认字面量：内核宏 `KSU_PACKAGE_NAME` 与 manager 的 applicationId
/// 必须逐字节一致，任何一处"顺手写死"都会在只改一半时静默失效。
const MANAGER_PACKAGE_SINGLE_SOURCE: &str = "../../manager-package.properties";

/// 从单一真源读取包名；`key` 为 `KSU_MANAGER_PACKAGE_NAME` 或 `KSU_MANAGER_PACKAGE_NAME_PR`。
fn manager_package_name(key: &str) -> String {
    let manifest_dir = env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR not set");
    let path = Path::new(&manifest_dir).join(MANAGER_PACKAGE_SINGLE_SOURCE);
    let text = fs::read_to_string(&path).unwrap_or_else(|error| {
        panic!(
            "cannot read the manager package single source {}: {error}; \
             build from the repository root or set KSU_PACKAGE_NAME explicitly",
            path.display()
        )
    });
    println!("cargo:rerun-if-changed={}", path.display());

    let prefix = format!("{key}=");
    text.lines()
        .filter_map(|line| line.trim().strip_prefix(&prefix))
        .map(str::trim)
        .find(|value| !value.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| panic!("{key} not found in {}", path.display()))
}

fn main() {
    assemble_bootstrap();

    let (code, name) = match get_git_version() {
        Ok((code, name)) => (code, name),
        Err(_) => {
            // show warning if git is not installed
            println!("cargo:warning=Failed to get git version, using 0.0.0");
            (0, "0.0.0".to_string())
        }
    };
    println!("cargo:rerun-if-env-changed=KSU_PACKAGE_NAME");
    // 注入的版本号来自 CI 的统一计算。必须声明 env 依赖，否则 rust-cache 复用
    // 旧编译产物时本脚本不会重跑，版本号会永远停在上一轮（实测 ksud 因此一直
    // 报 41151）。warning 打在日志里，方便出包后人工核对三处数字。
    println!("cargo:rerun-if-env-changed=KSU_VERSION_CODE");
    println!("cargo:warning=ksud version code: {code} ({name})");
    println!("cargo:rerun-if-env-changed=KSU_IS_PR_BUILD");
    if env::var("KSU_PACKAGE_NAME").is_err() {
        // PR 构建用 .pr 变体；与 Kbuild 的 KSU_IS_PR_BUILD 语义保持一致。
        let key = match env::var("KSU_IS_PR_BUILD").as_deref() {
            Ok("1") | Ok("true") => "KSU_MANAGER_PACKAGE_NAME_PR",
            _ => "KSU_MANAGER_PACKAGE_NAME",
        };
        println!("cargo:rustc-env=KSU_PACKAGE_NAME={}", manager_package_name(key));
    }
    println!("cargo:rustc-env=VERSION_CODE={code}");
    println!("cargo:rustc-env=VERSION_NAME={name}");

    let target_os = env::var("CARGO_CFG_TARGET_OS").expect("CARGO_CFG_TARGET_OS not set");
    if target_os == "android" {
        configure_bindgen();
    }
}
