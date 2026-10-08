from pathlib import Path
import re, sys
p = Path(sys.argv[1])
s = p.read_text(encoding="utf-8")

s = s.replace(
'''            public byte[] ReadCache;
            public long ReadCacheOffset = -1;
        }

        private const int ReadBlockSize = 4 * 1024 * 1024;''',
'''            public byte[] ReadCache;
            public long ReadCacheOffset = -1;
            public long LastReadEnd = -1;
            public int SequentialReadCount;
        }

        private const int MinReadBlockSize = 128 * 1024;
        private const int MidReadBlockSize = 512 * 1024;
        private const int LargeReadBlockSize = 1024 * 1024;
        private const int MaxReadBlockSize = 4 * 1024 * 1024;''', 1)

if "CleanupStaleTempFiles();" not in s:
    s = s.replace(
        "            _cacheDir = AppConfig.CacheDirectory;",
        "            _cacheDir = AppConfig.CacheDirectory;\n            CleanupStaleTempFiles();", 1)

m = re.search(r'(public override int Overwrite\(.*?\n        \{.*?lock \(h\)\n            \{)(.*?)(\n                h\.Stream\.SetLength\(0\);)', s, re.S)
if not m:
    raise SystemExit("Overwrite section not found")
mid = m.group(2).replace("EnsureLocal(h, createEmpty: false);", "EnsureLocal(h, createEmpty: true);", 1)
s = s[:m.start()] + m.group(1) + mid + m.group(3) + s[m.end():]

read_pat = re.compile(r'''        public override int Read\(object FileNode, object FileDesc0, IntPtr Buffer, ulong Offset, uint Length, out uint PBytesTransferred\)\n        \{.*?\n        \}\n\n        public override int Write\(''', re.S)
read_new = '''        public override int Read(object FileNode, object FileDesc0, IntPtr Buffer, ulong Offset, uint Length, out uint PBytesTransferred)
        {
            var h = (FtpHandle)FileDesc0;
            PBytesTransferred = 0;
            if (h.IsDirectory) return STATUS_FILE_IS_A_DIRECTORY;

            lock (h)
            {
                if (h.LocalReady && h.Stream != null)
                {
                    if (Offset >= (ulong)h.Stream.Length) return STATUS_SUCCESS;
                    int localCount = (int)Math.Min((ulong)Length, (ulong)h.Stream.Length - Offset);
                    byte[] local = new byte[localCount];
                    h.Stream.Position = (long)Offset;
                    int localRead = h.Stream.Read(local, 0, localCount);
                    if (localRead > 0) Marshal.Copy(local, 0, Buffer, localRead);
                    PBytesTransferred = (uint)localRead;
                    return STATUS_SUCCESS;
                }

                long fileSize = Math.Max(0, h.Entry.Size);
                if (Offset >= (ulong)fileSize || Length == 0) return STATUS_SUCCESS;

                int wanted = (int)Math.Min(
                    (ulong)Math.Min((long)Length, int.MaxValue),
                    (ulong)fileSize - Offset);

                long requestStart = checked((long)Offset);
                bool sequential = h.LastReadEnd >= 0 && requestStart == h.LastReadEnd;
                if (sequential) h.SequentialReadCount = Math.Min(h.SequentialReadCount + 1, 16);
                else h.SequentialReadCount = 0;

                int prefetch = AdaptiveReadSize(h.SequentialReadCount);
                byte[] output = new byte[wanted];
                int copied = 0;

                while (copied < wanted)
                {
                    long position = checked((long)Offset + copied);
                    bool cacheHit =
                        h.ReadCache != null &&
                        position >= h.ReadCacheOffset &&
                        position < h.ReadCacheOffset + h.ReadCache.Length;

                    if (!cacheHit)
                    {
                        int remainingWanted = wanted - copied;
                        int blockLength = Math.Max(remainingWanted, prefetch);
                        blockLength = (int)Math.Min((long)blockLength, fileSize - position);
                        if (blockLength <= 0) break;

                        h.ReadCache = _ftp.ReadRange(h.RemotePath, position, blockLength);
                        h.ReadCacheOffset = position;
                        if (h.ReadCache == null || h.ReadCache.Length == 0) break;
                    }

                    int cacheIndex = (int)(position - h.ReadCacheOffset);
                    int available = h.ReadCache.Length - cacheIndex;
                    if (available <= 0) break;

                    int take = Math.Min(available, wanted - copied);
                    System.Buffer.BlockCopy(h.ReadCache, cacheIndex, output, copied, take);
                    copied += take;

                    if (cacheIndex + take >= h.ReadCache.Length && h.ReadCache.Length < prefetch)
                        break;
                }

                if (copied > 0)
                    Marshal.Copy(output, 0, Buffer, copied);

                PBytesTransferred = (uint)copied;
                h.LastReadEnd = checked((long)Offset + copied);
                return STATUS_SUCCESS;
            }
        }

        public override int Write('''
s, n = read_pat.subn(read_new, s, count=1)
if n != 1:
    raise SystemExit("Read method not found")

s = s.replace(
"            _ftp.Rename(h.RemotePath, newPath);",
"            if (h.Uploaded)\n                _ftp.Rename(h.RemotePath, newPath);", 1)

if "private static int AdaptiveReadSize" not in s:
    marker = "        private void EnsureLocal(FtpHandle h, bool createEmpty)\n        {"
    insert = '''        private static int AdaptiveReadSize(int sequentialCount)
        {
            if (sequentialCount >= 6) return MaxReadBlockSize;
            if (sequentialCount >= 3) return LargeReadBlockSize;
            if (sequentialCount >= 1) return MidReadBlockSize;
            return MinReadBlockSize;
        }

        private void CleanupStaleTempFiles()
        {
            try
            {
                foreach (string file in Directory.GetFiles(_cacheDir, "*.tmp"))
                {
                    try { File.Delete(file); } catch { }
                }
            }
            catch { }
        }

        private void EnsureLocal(FtpHandle h, bool createEmpty)
        {'''
    if marker not in s:
        raise SystemExit("EnsureLocal marker not found")
    s = s.replace(marker, insert, 1)

p.write_text(s, encoding="utf-8")
