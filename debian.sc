/** Basic binary package */
trait Deb extends mill.Module {

  def installs: T[Agg[(String, os.Path)]] = T{Agg()}
  def writes: T[Agg[(String, String)]] = T{Agg()}
  
  def control: T[String]
  def postinst: T[String] = ""
  def postrm: T[String] = ""
  def prerm: T[String] = ""

  def rootfs: T[os.Path] = T {
    for ((archivePath, file) <- installs()) {
      os.copy.over(file, T.ctx.dest / os.SubPath(archivePath), createFolders = true)
    }
    for ((archivePath, content) <- writes()) {
      os.write.over(T.ctx.dest / os.SubPath(archivePath), content, createFolders = true)
    }
    T.ctx.dest
  }

  def deb: T[os.Path] = T {
    require(os.isDir(rootfs()), "rootfs must return a directory")

    val DEBIAN = rootfs() / "DEBIAN"
    os.write.over(DEBIAN / "control", control(), createFolders = true)

    if (postinst() != "") {
      os.write.over(DEBIAN / "postinst", "#!/bin/sh\nset -e\n" + postinst(), perms = "rwxr-xr-x", createFolders = true)
    }
    if (postrm() != "") {
      os.write.over(DEBIAN / "postrm", "#!/bin/sh\nset -e\n" + postrm(), perms = "rwxr-xr-x", createFolders = true)
    }
    if (prerm() != "") {
      os.write.over(DEBIAN / "prerm", "#!/bin/sh\nset -e\n" + prerm(), perms = "rwxr-xr-x", createFolders = true)
    }

    if (os.exists(rootfs() / "etc")) {
      val conffiles = os.walk(rootfs() / "etc").map(_.relativeTo(rootfs()))
      os.write.over(DEBIAN / "conffiles", conffiles.map(f => s"/$f\n").mkString)
    }

    val out = T.ctx().dest / "out.deb"
    os.proc("fakeroot", "dpkg-deb", "-Zgzip", "--build", rootfs(), out).call()
    os.proc(
      "lintian", "--info",
      "--suppress-tags", "no-copyright-file",
		  "--suppress-tags", "debian-changelog-file-missing",
		  "--suppress-tags", "executable-not-elf-or-script",
		  "--suppress-tags", "changelog-file-missing-in-native-package",
      out
    ).call(stdout = os.Inherit)
    out
  }
}

object Arch {
  val current: String =
    os.proc("dpkg-architecture", "--query", "DEB_HOST_ARCH").call().out.text.trim()
}

/** Sets the control field to common defaults */
trait StdControl extends Deb {

  def version: T[String]
  def description: T[String]
  def arch: T[String] = T {Arch.current}
  def debianDeps: T[Seq[String]] = T { Nil }

  def control = T {
    val b = new collection.mutable.StringBuilder
    b ++= s"Package: ${millSourcePath.last}\n"
    b ++= s"Section: misc\n"
    b ++= s"Priority: optional\n"
    b ++= s"Maintainer: Crashbox Packagers <ops@crashbox.io>\n"
    b ++= s"Version: ${version()}\n"
    b ++= s"Architecture: ${arch()}\n"
    if (!debianDeps().isEmpty) {
      b ++= s"Depends: ${debianDeps().mkString(", ")}\n"
    }
    b ++= s"Description: ${description()}\n"
    b ++= s" long: ${description()}\n"
    b.result()      
  }
}

trait StdBuild extends Deb {
  def sources: T[os.Path]

  def buildScript: T[String]
  
  def build = T.persistent{
    val scriptFile = T.ctx().dest / "build"
    os.write.over(scriptFile, buildScript(), perms = "rwxr-xr-x", createFolders = true)
    os.proc(scriptFile).call(cwd = sources(), stdout = os.Inherit) // build in source directory
    sources()
  }
}


case class SystemdUnit(name: String, content: String)
object SystemdUnit {
  implicit val unitRW: upickle.default.ReadWriter[SystemdUnit] = upickle.default.macroRW
}

trait Systemd extends Deb {

  def units: T[Agg[SystemdUnit]]

  private def services(units: Agg[SystemdUnit]): Agg[String] = {
    units.filter(_.name.matches("\\w+.service")).map(_.name)
  }

  def writes = T {
    super.writes() ++ units().map{ unit =>
      s"lib/systemd/system/${unit.name}" -> unit.content
    }
  }

  override def postinst = super.postinst() +
    s"""|if [ "$$1" = "configure" ] || [ "$$1" = "abort-upgrade" ] || [ "$$1" = "abort-deconfigure" ] || [ "$$1" = "abort-remove" ] ; then
        |	if [ -d /run/systemd/system ]; then
        |		systemctl --system daemon-reload >/dev/null || true
        |		if [ -n "$$2" ]; then
        |			_dh_action=restart
        |		else
        |			_dh_action=start
        |		fi
        |   ${services(units()).map( name => s"""deb-systemd-invoke $$_dh_action '$name' >/dev/null || true\n""").mkString}
        |	fi
        |fi
        |""".stripMargin
 
  override def postrm = super.postrm() +
    s"""|if [ -d /run/systemd/system ]; then
        |	systemctl --system daemon-reload >/dev/null || true
        |fi
        |""".stripMargin

  override def prerm = super.prerm() +
    s"""|if [ -d /run/systemd/system ] && [ "$$1" = remove ]; then
        |  ${services(units()).map( name => s"""deb-systemd-invoke stop '$name' >/dev/null || true\n""").mkString}
        |fi
        |""".stripMargin

}
