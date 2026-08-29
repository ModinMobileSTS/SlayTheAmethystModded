fn main() {
    println!("cargo:rerun-if-changed=windows.manifest");
    if std::env::var("TARGET")
        .map(|target| target.contains("windows"))
        .unwrap_or(false)
    {
        let mut resource = winres::WindowsResource::new();
        if let Ok(path) = std::env::var("WINDRES") {
            resource.set_windres_path(&path);
        }
        if let Ok(path) = std::env::var("AR") {
            resource.set_ar_path(&path);
        }
        resource.set_manifest_file("windows.manifest");
        resource
            .compile()
            .expect("failed to embed Windows application manifest");
        if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("gnu") {
            let object =
                std::path::Path::new(&std::env::var("OUT_DIR").unwrap()).join("resource.o");
            println!(
                "cargo:rustc-link-arg-bin=slay-the-amethyst-online={}",
                object.display()
            );
        }
    }
}
