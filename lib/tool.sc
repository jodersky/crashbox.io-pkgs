trait SourceTarball extends mill.Module {
  
  def url: T[String]

  def sourceArchive: T[PathRef] = T.input {
    util.wget(url())
  }

  def sourceDir: T[PathRef] = T.persistent {
    val src = T.ctx.dest / "src"

    // we explicitly check file checksums rather than relying on mill's task DAG,
    // as the latter is always reevaluated on a build.sc change
    util.stamped(sourceArchive()) {
      println("extracting")
      os.remove.all(src)
      os.makeDir.all(src)

      os.proc("tar", "-x", "-C", src, "-f", sourceArchive().path).call(stdout = os.Inherit)
    }

    val files = os.list(src)
    if (files.length == 1 && os.isDir(files.head)) { // many tarballs wrap content in another directory
      PathRef(files.head)
    } else {
      PathRef(src)
    }
  }

  def buildSteps: T[String]
  def buildScript: T[PathRef] = T {
    os.write(T.ctx.dest / "build", "#!/bin/sh\nset -e\n" + buildSteps(), perms = "rwxr-xr-x")
    PathRef(T.ctx.dest / "build")
  }

  def build = T.persistent {
    // some external build scripts don't handle caching well
    util.stamped(sourceArchive()){
      os.proc(buildScript().path).call(cwd = sourceDir().path, stdout = os.Inherit)
    }
    sourceDir()
  }
  
}
