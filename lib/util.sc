// download a file from the the given url and cache it permanently
// IMPORTANT: requires that the url be idempotent, i.e. that the contents of the linked file never change,
// in particular, note thate urls to "latest" versions of files are NOT allowed
// TODO: implement TOFU integrity check
def wget(url: String, force: Boolean = false): PathRef = {
  val store = os.home / ".cache" / "crashbox.io-pkgs" / "wget"
   // stamps mark completed downloads, this allows simple caching even when downloads are aborted
  val stamps = os.home / ".cache" / "crashbox.io-pkgs" / "stamps"

  val subpath = os.SubPath(url.replace(":", "/"))

  val file = store / subpath
  val stamp = stamps / subpath

  if (force || !os.exists(stamp) || !os.exists(file)) {
    println(s"downloading $url")
    os.write.over(
      file,
      requests.get.stream(url),
      createFolders = true
    )
    os.write.over(stamp, "", createFolders = true)
  }
  PathRef(file, quick = true) // since idempotent URLs are required, it is safe to use quick here
}

def stamped(keys: Any*)(action: => Any)(implicit ctx: mill.api.Ctx): PathRef = {
  val key = keys.map(_.hashCode).sum.toString // TODO don't use sum (maybe murmur hash?)
  val stampfile = ctx.dest / "stamp"
  if (!os.exists(stampfile) || os.read(stampfile) != key) {
    action
    os.write.over(stampfile, key)
  }
  PathRef(stampfile)
}
