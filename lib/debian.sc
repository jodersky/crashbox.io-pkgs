import $file.util

/** Basic binary package */
trait Deb extends mill.Module {

  def installs: T[Agg[(String, PathRef)]] = T{Agg()}
  def writes: T[Agg[(String, String)]] = T{Agg()}
  
  def control: T[String]
  def postinst: T[String] = ""
  def postrm: T[String] = ""
  def prerm: T[String] = ""

  def exploded: T[PathRef] = T {
    val root = T.ctx.dest
    val DEBIAN = root / "DEBIAN"

    for ((archivePath, ref) <- installs()) {
      os.copy.over(ref.path, root / os.SubPath(archivePath), createFolders = true)
    }
    for ((archivePath, content) <- writes()) {
      os.write.over(root / os.SubPath(archivePath), content, createFolders = true)
    }
    
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

    if (os.exists(root / "etc")) {
      val conffiles = os.walk(root / "etc").map(_.relativeTo(root))
      os.write.over(DEBIAN / "conffiles", conffiles.map(f => s"/$f\n").mkString)
    }
    PathRef(root)
  }

  def showExploded() = T.command {
    os.walk(exploded().path).foreach{p =>
      if (!os.isDir(p)) {
        println(p)
      }
    }
  }

  def dpkgdeb = T {
    val out = T.ctx.dest / "out.deb"
    os.proc("fakeroot", "dpkg-deb", "-Zgzip", "--build", exploded().path, out)
      .call(env = Map("SOURCE_DATE_EPOCH" -> "1587943600")) // 2020-04-27, arbitrary
    PathRef(out)
  }


  def lintianIgnores: T[Agg[String]] = T {
    Agg(
      "binary-without-manpage",
      "no-copyright-file",
      "debian-changelog-file-missing",
      "executable-not-elf-or-script",
      "changelog-file-missing-in-native-package"
    )
  }

  def deb: T[PathRef] = T.persistent {
    val suppressTags = lintianIgnores().toSeq.map{ str =>
      Seq("--suppress-tags", str)
    }
    util.stamped(dpkgdeb(), lintianIgnores()) {
      os.proc(
        "lintian", "--info",
        suppressTags,
        dpkgdeb().path
      ).call(stdout = os.Inherit)
    }
    dpkgdeb()
  }

}

object Arch {
  val current: String =
    os.proc("dpkg-architecture", "--query", "DEB_HOST_ARCH").call().out.text.trim()
}

/** Sets the control field to common defaults */
trait StdControl extends Deb {

  def name: T[String] = T { millSourcePath.last }
  def version: T[String]
  def description: T[String]
  def arch: T[String] = T {Arch.current}
  def debianDeps: T[Agg[String]] = T { Agg.empty }

  def control = T {
    val b = new collection.mutable.StringBuilder
    b ++= s"Package: ${name()}\n"
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

case class SystemdUnit(name: String, content: String)
object SystemdUnit {
  implicit val unitRW: upickle.default.ReadWriter[SystemdUnit] = upickle.default.macroRW
}

trait Systemd extends Deb {

  def units: T[Agg[SystemdUnit]]

  def writes = T {
    super.writes() ++ units().map{ unit =>
      s"lib/systemd/system/${unit.name}" -> unit.content
    }
  }

  override def postinst = T {
    val services = units().filter(_.name.matches("\\w+.service")).map(_.name)
    if (services.isEmpty) {
      super.postinst()
    } else {
      super.postinst() +
      s"""|if [ "$$1" = "configure" ] || [ "$$1" = "abort-upgrade" ] || [ "$$1" = "abort-deconfigure" ] || [ "$$1" = "abort-remove" ] ; then
          |	if [ -d /run/systemd/system ]; then
          |		systemctl --system daemon-reload >/dev/null || true
          |		if [ -n "$$2" ]; then
          |			_dh_action=restart
          |		else
          |			_dh_action=start
          |		fi
          |   ${services.map( name => s"""deb-systemd-invoke $$_dh_action '$name' >/dev/null || true\n""").mkString}
          |	fi
          |fi
          |""".stripMargin
    }
  }

  override def postrm = T {
    val services = units().filter(_.name.matches("\\w+.service")).map(_.name)
    if (services.isEmpty) {
      super.postrm()
    } else {
      super.postrm() +
      s"""|if [ -d /run/systemd/system ]; then
          |	systemctl --system daemon-reload >/dev/null || true
          |fi
          |""".stripMargin
    }
  }

  override def prerm = T {
    val services = units().filter(_.name.matches("\\w+.service")).map(_.name)
    if (services.isEmpty) {
      super.prerm()
    } else {
      super.prerm() +
      s"""|if [ -d /run/systemd/system ] && [ "$$1" = remove ]; then
          |  ${services.map( name => s"""deb-systemd-invoke stop '$name' >/dev/null || true\n""").mkString}
          |fi
          |""".stripMargin
    }
  }

}

trait Package extends StdControl with Systemd {

  def assets: T[PathRef] = T.source(millSourcePath / "assets")

  def allAssets: T[Agg[PathRef]] = T {
    val dir = assets().path
    if (os.exists(dir) && os.isDir(dir)) {
      os.walk(dir).map(p => PathRef(p))
    } else {
      Seq.empty
    }
  }

  def installs = T {
    super.installs() ++ allAssets().map{ absolute =>
      val rel = absolute.path.relativeTo(assets().path)
      rel.toString -> absolute
    }
  }

  def config: T[PathRef] = T.source(millSourcePath / "config")

  def allConfigFiles: T[Seq[PathRef]] = T {
    val dir = config().path
    if (os.exists(dir) && os.isDir(dir)) {
       os.list(dir).map(p => PathRef(p))
    } else {
      Seq.empty
    }
  }

  def postinst = T {
    allConfigFiles().find(_.path.last == "postinst") match {
      case None => super.postinst()
      case Some(snippet) => os.read(snippet.path) + super.postinst()
    }
  }

  def postrm = T {
    allConfigFiles().find(_.path.last == "postrm") match {
      case None => super.postrm()
      case Some(snippet) => super.postrm() + os.read(snippet.path)
    }
  }

  def prerm = T {
    allConfigFiles().find(_.path.last == "prerm") match {
      case None => super.prerm()
      case Some(snippet) => super.prerm() + os.read(snippet.path)
    }
  }

  def units = T {
    val unitFiles = allConfigFiles().filter(f => f.path.ext == "service" || f.path.ext == "timer")
    unitFiles.map(f => SystemdUnit(f.path.last, os.read(f.path)))
  }

}
