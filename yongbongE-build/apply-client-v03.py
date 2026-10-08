from pathlib import Path
import re, sys
p = Path(sys.argv[1])
s = p.read_text(encoding="utf-8")

if "DirectoryCacheLifetime" not in s:
    s = s.replace(
        "        private readonly Encoding _strictUtf8 = new UTF8Encoding(false, true);",
        "        private readonly Encoding _strictUtf8 = new UTF8Encoding(false, true);\n        private static readonly TimeSpan DirectoryCacheLifetime = TimeSpan.FromSeconds(30);"
    )

pattern = re.compile(r'''        public RemoteEntry Stat\(string path\)\n        \{.*?\n        public void Download\(string remotePath, string localPath\)''', re.S)
replacement = '''        public RemoteEntry Stat(string path)
        {
            path = NormalizePath(path);
            if (path == "/")
                return new RemoteEntry { Name = "", FullPath = "/", IsDirectory = true, Size = 0, ModifiedUtc = DateTime.UtcNow };

            string parent = ParentPath(path);
            string leaf = LeafName(path);
            lock (_sync)
            {
                List<RemoteEntry> list = GetDirectoryEntriesNoLock(parent);
                foreach (RemoteEntry e in list)
                    if (string.Equals(e.Name, leaf, StringComparison.OrdinalIgnoreCase))
                        return CloneEntry(e);
                return null;
            }
        }

        public List<RemoteEntry> List(string path)
        {
            path = NormalizePath(path);
            lock (_sync)
                return CloneEntries(GetDirectoryEntriesNoLock(path));
        }

        private List<RemoteEntry> GetDirectoryEntriesNoLock(string path)
        {
            CacheItem cached;
            if (_dirCache.TryGetValue(path, out cached) && cached.ExpiresUtc > DateTime.UtcNow)
                return cached.Entries;

            List<RemoteEntry> result = RunWithReconnectNoLock(() => ListNoLock(path));
            _dirCache[path] = new CacheItem
            {
                ExpiresUtc = DateTime.UtcNow.Add(DirectoryCacheLifetime),
                Entries = result
            };
            return result;
        }

        public void Download(string remotePath, string localPath)'''
s, n = pattern.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit("Stat/List section not found")

s = s.replace("                    ns.ReadTimeout = 5000;", "                    ns.ReadTimeout = 8000;", 1)

if "private static RemoteEntry CloneEntry" not in s:
    marker = "        private static List<RemoteEntry> CloneEntries(List<RemoteEntry> src)\n        {"
    insert = '''        private static RemoteEntry CloneEntry(RemoteEntry e)
        {
            return new RemoteEntry
            {
                Name = e.Name,
                FullPath = e.FullPath,
                IsDirectory = e.IsDirectory,
                Size = e.Size,
                ModifiedUtc = e.ModifiedUtc
            };
        }

        private static List<RemoteEntry> CloneEntries(List<RemoteEntry> src)
        {'''
    if marker not in s:
        raise SystemExit("CloneEntries marker not found")
    s = s.replace(marker, insert, 1)

p.write_text(s, encoding="utf-8")
