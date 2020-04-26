import $file.debian
import mill._

object dummy extends debian.StdControl with debian.Systemd {
  def version = "0.0.1"
  def description = "test package"
  def units = Agg(
    debian.SystemdUnit("dummy.service", "")
  )
  def buildScript = "echo hello world > dummy"
  def writes = super.writes() ++ Agg("foo" -> "yo")
}

// object prometheus extends debian.StdControl with debian.StdBuild {
  
//   def version = "2.17.2"
//   def description = "monitoring system"

//   def sources = T.persistent{
//     os.proc("wget",
//       s"https://github.com/prometheus/prometheus/archive/v${version()}.tar.gz", "-O",
//       T.ctx.dest / "src.tar.gz"
//     ).call()
//     os.proc(
//       "tar", "xzf", T.ctx().dest / "src.tar.gz"
//     ).call(cwd = T.ctx.dest)
//     T.ctx.dest / s"prometheus-${version()}"
//   }

//   def buildScript = """make build"""
  
//   def rootfs = T {
//     os.copy(build() / "prometheus", T.ctx.dest / "usr" / "bin" / "promethus", createFolders = true)
//     T.ctx.dest
//   }

// }
