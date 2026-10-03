use anyhow::{Context, Result};
use std::{net::SocketAddr, path::PathBuf};
use voxy_rewrite_server::{Backend, wire};

#[tokio::main]
async fn main() -> Result<()> {
    let mut world = None;
    let mut data = None;
    let mut listen: SocketAddr = "0.0.0.0:25787".parse()?;
    let mut once = false;
    let mut refresh_ms = 1000;
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--world" => world = Some(PathBuf::from(args.next().context("--world value")?)),
            "--data" => data = Some(PathBuf::from(args.next().context("--data value")?)),
            "--listen" => listen = args.next().context("--listen value")?.parse()?,
            "--refresh-ms" => refresh_ms = args.next().context("--refresh-ms value")?.parse()?,
            "--once" => once = true,
            _ => anyhow::bail!(
                "Usage: voxy-rewrite-server --world PATH --data PATH [--listen SOCKET] [--refresh-ms N] [--once]"
            ),
        }
    }
    let world = world.context("--world required")?;
    let data = data.context("--data required")?;
    if once {
        println!(
            "VOXY_IMPORTED sections={}",
            Backend::build_all(&world, &data)?
        );
    } else {
        wire::serve(
            Backend::open_with_refresh(
                &world,
                &data,
                std::time::Duration::from_millis(refresh_ms),
            )?,
            listen,
        )
        .await?;
    }
    Ok(())
}
