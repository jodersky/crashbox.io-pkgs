import $file.lib.debian
import $file.lib.util
import $file.lib.tool

object packages extends mill.Module {

  object dummy extends debian.Package {
    def version = "0.0.1"
    def description = "test package"
    def debianDeps = Agg("adduser")
  }

  object prometheus extends debian.Package with tool.SourceTarball {
    def version = "2.17.2"
    def description = "monitoring tool"
    def url = s"https://github.com/prometheus/prometheus/archive/v${version()}.tar.gz"
    def buildSteps = "make build && strip prometheus && strip promtool"
    def installs = super.installs() ++ Agg(
      "usr/bin/prometheus" -> PathRef(build().path / "prometheus"),
      "usr/bin/promtool" -> PathRef(build().path / "promtool")
    )
    def lintianIgnores = super.lintianIgnores() ++ Agg("statically-linked-binary")
    def debianDeps = Agg("adduser")
  }

  object alertmanager extends debian.Package with tool.SourceTarball {
    def version = "0.20.0"
    def description = "prometheus alertmanager"
    def url = s"https://github.com/prometheus/alertmanager/archive/v${version()}.tar.gz"
    def buildSteps = "make build && strip alertmanager && strip amtool"
    def installs = super.installs() ++ Agg(
      "usr/bin/alertmanager" -> PathRef(build().path / "alertmanager"),
      "usr/bin/amtool" -> PathRef(build().path / "amtool")
    )
    def lintianIgnores = super.lintianIgnores() ++ Agg("statically-linked-binary")
  }

}
