using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Text;
using Fsp;
using VolumeInfo = Fsp.Interop.VolumeInfo;
using FileInfo = Fsp.Interop.FileInfo;

namespace TinyFtpDrive
{
    internal sealed class FtpFileSystem : FileSystemBase, IDisposable
    {
        private sealed class DirectoryEnumerationState
        {
            public List<RemoteEntry> Entries;
            public int Index;
        }

        private sealed class FtpHandle
        {
            public string RemotePath;
            public bool IsDirectory;
            public RemoteEntry Entry;
            public string TempPath;
            public FileStream Stream;
            public bool LocalReady;
            public bool Dirty;
            public bool Created;
            public bool Uploaded;
            public bool DeletePending;
            public DirectoryBuffer DirectoryBuffer;
            public byte[] ReadCache;
            public long ReadCacheOffset = -1;
            public long LastReadEnd = -1;
            public int SequentialReadCount;
            public LightweightFtpClient.ReadSession ReadSession;
            public LightweightFtpClient.UploadSession WriteSession;
            public bool StreamWriteEligible;
            public long ExpectedWriteSize;
            public bool IsZoneStream;
            public bool IsPendingReader;
        }

        private const int MinReadBlockSize = 32 * 1024;
        private const int MidReadBlockSize = 256 * 1024;
        private const int LargeReadBlockSize = 1024 * 1024;
        private const int MaxReadBlockSize = 4 * 1024 * 1024;
        private const long MaxRuntimeLogBytes = 1024L * 1024L;

        private sealed class SharedReadCache
        {
            private const int MaxBytes = 16 * 1024 * 1024;
            public const int MaxBlockBytes = 64 * 1024;

            private sealed class Item
            {
                public string Key;
                public byte[] Data;
            }

            private readonly object _sync = new object();
            private readonly Dictionary<string, LinkedListNode<Item>> _items = new Dictionary<string, LinkedListNode<Item>>(StringComparer.Ordinal);
            private readonly LinkedList<Item> _lru = new LinkedList<Item>();
            private int _bytes;

            private static string Key(string path, DateTime modifiedUtc, long offset)
            {
                return path + "|" + modifiedUtc.Ticks.ToString() + "|" + offset.ToString();
            }

            public bool TryGet(string path, DateTime modifiedUtc, long offset, int wanted, out byte[] data)
            {
                data = null;
                string key = Key(path, modifiedUtc, offset);
                lock (_sync)
                {
                    LinkedListNode<Item> node;
                    if (!_items.TryGetValue(key, out node) || node.Value.Data.Length < wanted)
                        return false;
                    _lru.Remove(node);
                    _lru.AddFirst(node);
                    data = node.Value.Data;
                    return true;
                }
            }

            public void Put(string path, DateTime modifiedUtc, long offset, byte[] data)
            {
                if (data == null || data.Length == 0 || data.Length > MaxBlockBytes) return;
                string key = Key(path, modifiedUtc, offset);
                lock (_sync)
                {
                    LinkedListNode<Item> existing;
                    if (_items.TryGetValue(key, out existing))
                    {
                        _bytes -= existing.Value.Data.Length;
                        _lru.Remove(existing);
                        _items.Remove(key);
                    }

                    var node = new LinkedListNode<Item>(new Item { Key = key, Data = data });
                    _lru.AddFirst(node);
                    _items[key] = node;
                    _bytes += data.Length;

                    while (_bytes > MaxBytes && _lru.Last != null)
                    {
                        LinkedListNode<Item> last = _lru.Last;
                        _lru.RemoveLast();
                        _items.Remove(last.Value.Key);
                        _bytes -= last.Value.Data.Length;
                    }
                }
            }

            public void Clear()
            {
                lock (_sync)
                {
                    _items.Clear();
                    _lru.Clear();
                    _bytes = 0;
                }
            }

            public void Invalidate(string path)
            {
                lock (_sync)
                {
                    var remove = new List<string>();
                    string prefix = path + "|";
                    foreach (string key in _items.Keys)
                        if (key.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) remove.Add(key);
                    foreach (string key in remove)
                    {
                        LinkedListNode<Item> node = _items[key];
                        _bytes -= node.Value.Data.Length;
                        _lru.Remove(node);
                        _items.Remove(key);
                    }
                }
            }
        }

        private readonly LightweightFtpClient _ftp;
        private readonly string _cacheDir;
        private readonly string _adsDir;
        private readonly SharedReadCache _sharedReadCache = new SharedReadCache();
        private readonly object _adsSync = new object();
        private readonly object _pendingSync = new object();
        private readonly Dictionary<string, FtpHandle> _pendingFiles =
            new Dictionary<string, FtpHandle>(StringComparer.OrdinalIgnoreCase);

        public FtpFileSystem(AppConfig cfg)
        {
            TraceLog.StartNewSession();
            TraceLog.Write("FS", "FtpFileSystem CONSTRUCT");
            _ftp = new LightweightFtpClient(cfg);
            _cacheDir = AppConfig.CacheDirectory;
            _adsDir = Path.Combine(AppConfig.ConfigDirectory, "ads");
            CleanupStaleTempFiles();
        }

        public void Dispose() => _ftp.Dispose();
        public void ResetConnection()
        {
            _sharedReadCache.Clear();
            _ftp.ResetConnection();
        }

        public override int ExceptionHandler(Exception ex)
        {
            TraceLog.Write("EXCEPTION", ex.GetType().FullName + ": " + ex.Message);
            WriteRuntimeError(ex);

            var ftp = ex as FtpException;
            if (ftp != null)
            {
                if (ftp.Code == 530) return STATUS_ACCESS_DENIED;
                if (ftp.Code == 550) return STATUS_OBJECT_NAME_NOT_FOUND;
                if (ftp.Code == 421 || ftp.Code == 425 || ftp.Code == 426) return STATUS_NETWORK_UNREACHABLE;
                return STATUS_UNEXPECTED_IO_ERROR;
            }
            if (ex is TimeoutException) return STATUS_IO_TIMEOUT;
            if (ex is SocketException || ex is EndOfStreamException) return STATUS_NETWORK_UNREACHABLE;
            if (ex is UnauthorizedAccessException) return STATUS_ACCESS_DENIED;
            if (ex is IOException) return STATUS_UNEXPECTED_IO_ERROR;
            return STATUS_UNEXPECTED_IO_ERROR;
        }

        private static readonly object RuntimeLogSync = new object();

        private static void WriteRuntimeError(Exception ex)
        {
            try
            {
                lock (RuntimeLogSync)
                {
                    string path = Path.Combine(AppConfig.ConfigDirectory, "error.log");
                    if (File.Exists(path) && new System.IO.FileInfo(path).Length >= MaxRuntimeLogBytes)
                        File.Delete(path);
                    File.AppendAllText(path, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff") + "  " + ex + Environment.NewLine);
                }
            }
            catch { }
        }

        public override int Init(object Host0)
        {
            TraceLog.Write("CB", "Init");
            var host = (FileSystemHost)Host0;
            host.SectorSize = 4096;
            host.SectorsPerAllocationUnit = 1;
            host.MaxComponentLength = 255;
            // RaiDrive-mode: its CallbackFS configuration disables metadata caching.
            // Keep the persistent DirectoryBuffer for enumeration state, but do not let
            // WinFsp metadata TTLs hide backend behavior during this fidelity test.
            host.FileInfoTimeout = 0;
            host.DirInfoTimeout = 0;
            host.SecurityTimeout = 0;
            host.StreamInfoTimeout = 0;
            host.EaTimeout = 0;
            host.CaseSensitiveSearch = false;
            host.CasePreservedNames = true;
            host.UnicodeOnDisk = true;
            host.NamedStreams = false; // FTP drive: do not advertise NTFS alternate data stream support.
            host.PersistentAcls = false;
            host.PostCleanupWhenModifiedOnly = true;
            host.PassQueryDirectoryPattern = false;
            host.FlushAndPurgeOnCleanup = false;
            host.VolumeCreationTime = (ulong)DateTime.UtcNow.ToFileTimeUtc();
            host.VolumeSerialNumber = 0x54465450; // TFTP
            // v0.16 NET-TRACE: non-empty Prefix makes WinFsp create WinFsp.Net
            // instead of WinFsp.Disk while keeping the drive-letter mount.
            host.Prefix = @"\yongbongE\ftp";
            host.FileSystemName = "yongbongE-NETTRACE";
            TraceLog.Write("CB", "Init Prefix=" + host.Prefix + " backend=WinFsp.Net");
            return STATUS_SUCCESS;
        }

        public override int GetVolumeInfo(out VolumeInfo VolumeInfo)
        {
            // FTP에는 표준 디스크 용량 조회가 없으므로 네트워크 접속 없이 고정 표시값만 반환한다.
            VolumeInfo = default(VolumeInfo);
            VolumeInfo.TotalSize = 1UL << 40; // 1 TiB (표시용)
            VolumeInfo.FreeSize = 1UL << 40;
            VolumeInfo.SetVolumeLabel("yongbongE");
            return STATUS_SUCCESS;
        }

        public override int GetSecurityByName(string FileName, out uint FileAttributes, ref byte[] SecurityDescriptor)
        {
            TraceLog.Write("CB", "GetSecurityByName BEGIN name=" + FileName);
            string baseName, streamName;
            if (TryGetNamedStream(FileName, out baseName, out streamName))
            {
                if (!IsZoneStreamName(streamName))
                {
                    FileAttributes = 0;
                    return STATUS_OBJECT_NAME_NOT_FOUND;
                }

                string sidecar = ZoneSidecarPath(ToRemotePath(baseName));
                if (!File.Exists(sidecar))
                {
                    FileAttributes = 0;
                    return STATUS_OBJECT_NAME_NOT_FOUND;
                }

                FileAttributes = (uint)System.IO.FileAttributes.Archive;
                SecurityDescriptor = null;
                return STATUS_SUCCESS;
            }

            FileName = StripDefaultDataStream(FileName);
            string path = ToRemotePath(FileName);

            FtpHandle pendingSecurity;
            if (TryGetPending(path, out pendingSecurity))
            {
                FileAttributes = (uint)System.IO.FileAttributes.Archive;
                SecurityDescriptor = null;
                return STATUS_SUCCESS;
            }

            RemoteEntry entry = path == "/" ? RootEntry() : _ftp.Stat(path);
            TraceLog.Write("CB", "GetSecurityByName STAT path=" + path + " result=" + (entry == null ? "null" : (entry.IsDirectory ? "DIR" : "FILE")));
            if (entry == null)
            {
                FileAttributes = 0;
                return STATUS_OBJECT_NAME_NOT_FOUND;
            }
            FileAttributes = EntryAttributes(entry);
            SecurityDescriptor = null;
            return STATUS_SUCCESS;
        }

        public override int Create(
            string FileName, uint CreateOptions, uint GrantedAccess, uint FileAttributes, byte[] SecurityDescriptor, ulong AllocationSize,
            out object FileNode, out object FileDesc0, out FileInfo FileInfo, out string NormalizedName)
        {
            string baseName, streamName;
            if (TryGetNamedStream(FileName, out baseName, out streamName))
            {
                if (!IsZoneStreamName(streamName))
                {
                    FileNode = null; FileDesc0 = null; FileInfo = default(FileInfo); NormalizedName = null;
                    return STATUS_INVALID_DEVICE_REQUEST;
                }

                FtpHandle zh = OpenZoneStreamHandle(baseName, true);
                if (AllocationSize > 0) zh.Stream.SetLength((long)Math.Min(AllocationSize, (ulong)long.MaxValue));
                FileNode = null;
                FileDesc0 = zh;
                FileInfo = MakeZoneStreamFileInfo(zh);
                NormalizedName = null;
                return STATUS_SUCCESS;
            }

            FileName = StripDefaultDataStream(FileName);
            string path = ToRemotePath(FileName);
            bool isDir = 0 != (CreateOptions & FILE_DIRECTORY_FILE);
            FtpHandle existingPending;
            if (TryGetPending(path, out existingPending) || _ftp.Stat(path) != null)
            {
                FileNode = null; FileDesc0 = null; FileInfo = default(FileInfo); NormalizedName = null;
                return STATUS_OBJECT_NAME_COLLISION;
            }

            var h = new FtpHandle
            {
                RemotePath = path,
                IsDirectory = isDir,
                Entry = new RemoteEntry { Name = LightweightFtpClient.LeafName(path), FullPath = path, IsDirectory = isDir, Size = 0, ModifiedUtc = DateTime.UtcNow },
                Created = true,
                Uploaded = false,
                DirectoryBuffer = isDir ? new DirectoryBuffer() : null
            };

            if (isDir)
            {
                _ftp.MakeDirectory(path);
                h.Uploaded = true;
            }
            else
            {
                EnsureLocal(h, createEmpty: true);
                // AllocationSize is reserved capacity, not logical EOF. FTP does not expose
                // allocation, so keep the file at size 0 until SetFileSize/Write grows it.
                h.ExpectedWriteSize = 0;
                h.StreamWriteEligible = true;
                h.Dirty = true;
                RegisterPending(h);
            }

            FileNode = null;
            FileDesc0 = h;
            FileInfo = MakeFileInfo(h);
            NormalizedName = null;
            return STATUS_SUCCESS;
        }

        public override int Open(
            string FileName, uint CreateOptions, uint GrantedAccess,
            out object FileNode, out object FileDesc0, out FileInfo FileInfo, out string NormalizedName)
        {
            TraceLog.Write("CB", "Open BEGIN name=" + FileName + " options=0x" + CreateOptions.ToString("X8") + " access=0x" + GrantedAccess.ToString("X8"));
            string baseName, streamName;
            if (TryGetNamedStream(FileName, out baseName, out streamName))
            {
                if (!IsZoneStreamName(streamName))
                {
                    FileNode = null; FileDesc0 = null; FileInfo = default(FileInfo); NormalizedName = null;
                    return STATUS_OBJECT_NAME_NOT_FOUND;
                }

                string sidecar = ZoneSidecarPath(ToRemotePath(baseName));
                if (!File.Exists(sidecar))
                {
                    FileNode = null; FileDesc0 = null; FileInfo = default(FileInfo); NormalizedName = null;
                    return STATUS_OBJECT_NAME_NOT_FOUND;
                }

                FtpHandle zh = OpenZoneStreamHandle(baseName, false);
                FileNode = null;
                FileDesc0 = zh;
                FileInfo = MakeZoneStreamFileInfo(zh);
                NormalizedName = null;
                return STATUS_SUCCESS;
            }

            FileName = StripDefaultDataStream(FileName);
            string path = ToRemotePath(FileName);

            FtpHandle pendingOwner;
            if (TryGetPending(path, out pendingOwner))
            {
                FtpHandle pendingHandle = OpenPendingReadHandle(pendingOwner);
                if (pendingHandle != null)
                {
                    if (0 != (CreateOptions & FILE_DIRECTORY_FILE))
                    {
                        try { pendingHandle.Stream?.Dispose(); } catch { }
                        FileNode = null; FileDesc0 = null; FileInfo = default(FileInfo); NormalizedName = null;
                        return STATUS_NOT_A_DIRECTORY;
                    }
                    FileNode = null;
                    FileDesc0 = pendingHandle;
                    FileInfo = MakeFileInfo(pendingHandle);
                    NormalizedName = null;
                    return STATUS_SUCCESS;
                }
            }

            RemoteEntry entry = path == "/" ? RootEntry() : _ftp.Stat(path);
            TraceLog.Write("CB", "Open STAT path=" + path + " result=" + (entry == null ? "null" : (entry.IsDirectory ? "DIR" : "FILE")));
            if (entry == null)
            {
                FileNode = null; FileDesc0 = null; FileInfo = default(FileInfo); NormalizedName = null;
                return STATUS_OBJECT_NAME_NOT_FOUND;
            }
            if (0 != (CreateOptions & FILE_DIRECTORY_FILE) && !entry.IsDirectory)
            {
                FileNode = null; FileDesc0 = null; FileInfo = default(FileInfo); NormalizedName = null;
                return STATUS_NOT_A_DIRECTORY;
            }
            if (0 != (CreateOptions & FILE_NON_DIRECTORY_FILE) && entry.IsDirectory)
            {
                FileNode = null; FileDesc0 = null; FileInfo = default(FileInfo); NormalizedName = null;
                return STATUS_FILE_IS_A_DIRECTORY;
            }

            var h = new FtpHandle
            {
                RemotePath = path,
                IsDirectory = entry.IsDirectory,
                Entry = entry,
                Uploaded = true,
                DirectoryBuffer = entry.IsDirectory ? new DirectoryBuffer() : null
            };
            FileNode = null;
            FileDesc0 = h;
            FileInfo = MakeFileInfo(h);
            NormalizedName = null;
            return STATUS_SUCCESS;
        }

        public override int Overwrite(object FileNode, object FileDesc0, uint FileAttributes, bool ReplaceFileAttributes, ulong AllocationSize, out FileInfo FileInfo)
        {
            var h = (FtpHandle)FileDesc0;
            if (h.IsZoneStream)
            {
                lock (h)
                {
                    h.Stream.SetLength(0);
                    if (AllocationSize > 0) h.Stream.SetLength((long)Math.Min(AllocationSize, (ulong)long.MaxValue));
                    FileInfo = MakeZoneStreamFileInfo(h);
                    return STATUS_SUCCESS;
                }
            }
            if (h.IsDirectory)
            {
                FileInfo = default(FileInfo);
                return STATUS_FILE_IS_A_DIRECTORY;
            }
            lock (h)
            {
                CloseWriteSession(h);
                EnsureLocal(h, createEmpty: true);
                h.Stream.SetLength(0);
                h.ExpectedWriteSize = 0;
                h.StreamWriteEligible = true;
                h.Dirty = true;
                h.Entry.Size = h.Stream.Length;
                h.Entry.ModifiedUtc = DateTime.UtcNow;
                FileInfo = MakeFileInfo(h);
            }
            return STATUS_SUCCESS;
        }

        public override void Cleanup(object FileNode, object FileDesc0, string FileName, uint Flags)
        {
            var h = FileDesc0 as FtpHandle;
            if (h == null) return;
            if (h.IsZoneStream)
            {
                if (0 != (Flags & CleanupDelete))
                {
                    lock (h)
                    {
                        try { h.Stream?.Dispose(); } catch { }
                        h.Stream = null;
                        try { if (!string.IsNullOrEmpty(h.TempPath)) File.Delete(h.TempPath); } catch { }
                    }
                }
                return;
            }
            if (0 == (Flags & CleanupDelete)) return;
            lock (h)
            {
                CloseReadSession(h);
                CloseWriteSession(h);
                h.DeletePending = true;
                UnregisterPending(h);
                try { h.Stream?.Dispose(); } catch { }
                h.Stream = null;
                if (h.Uploaded)
                {
                    if (h.IsDirectory) _ftp.RemoveDirectory(h.RemotePath);
                    else _ftp.DeleteFile(h.RemotePath);
                    _sharedReadCache.Invalidate(h.RemotePath);
                    h.Uploaded = false;
                }
                DeleteZoneMetadata(h.RemotePath, h.IsDirectory);
            }
        }

        public override void Close(object FileNode, object FileDesc0)
        {
            var h = FileDesc0 as FtpHandle;
            if (h == null) return;
            TraceLog.Write("CB", "Close path=" + h.RemotePath + " dir=" + h.IsDirectory);
            if (h.IsZoneStream)
            {
                lock (h)
                {
                    try { h.Stream?.Flush(true); } catch { }
                    try { h.Stream?.Dispose(); } catch { }
                    h.Stream = null;
                }
                return;
            }
            lock (h)
            {
                try
                {
                    if (!h.DeletePending && h.Dirty && !h.IsDirectory)
                    {
                        if (h.WriteSession != null) FinishStreamingUpload(h);
                        else UploadDirty(h);
                    }
                }
                catch (Exception ex)
                {
                    ExceptionHandler(ex);
                    try
                    {
                        CloseWriteSession(h);
                        if (!h.DeletePending && h.Dirty && !h.IsDirectory) UploadDirty(h);
                    }
                    catch (Exception fallbackEx) { ExceptionHandler(fallbackEx); }
                }
                finally
                {
                    UnregisterPending(h);
                    CloseReadSession(h);
                    CloseWriteSession(h);
                    try { h.Stream?.Dispose(); } catch { }
                    h.Stream = null;
                    h.ReadCache = null;
                    h.ReadCacheOffset = -1;
                    try { h.DirectoryBuffer?.Dispose(); } catch { }
                    h.DirectoryBuffer = null;
                    if (!string.IsNullOrEmpty(h.TempPath))
                    {
                        try { File.Delete(h.TempPath); } catch { }
                    }
                }
            }
        }

        public override int Read(object FileNode, object FileDesc0, IntPtr Buffer, ulong Offset, uint Length, out uint PBytesTransferred)
        {
            var h = (FtpHandle)FileDesc0;
            PBytesTransferred = 0;
            TraceLog.Write("CB", "Read path=" + h.RemotePath + " off=" + Offset + " len=" + Length);
            if (h.IsZoneStream)
            {
                lock (h)
                {
                    if (h.Stream == null || Offset >= (ulong)h.Stream.Length || Length == 0) return STATUS_SUCCESS;
                    int count = (int)Math.Min((ulong)Length, (ulong)h.Stream.Length - Offset);
                    byte[] data = new byte[count];
                    h.Stream.Position = (long)Offset;
                    int read = h.Stream.Read(data, 0, count);
                    if (read > 0) Marshal.Copy(data, 0, Buffer, read);
                    PBytesTransferred = (uint)read;
                    return STATUS_SUCCESS;
                }
            }
            if (h.IsDirectory) return STATUS_FILE_IS_A_DIRECTORY;

            lock (h)
            {
                if (h.LocalReady && h.Stream != null)
                {
                    CloseReadSession(h);
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
                if (Offset >= (ulong)fileSize || Length == 0)
                {
                    CloseReadSession(h);
                    return STATUS_SUCCESS;
                }

                int wanted = (int)Math.Min(
                    (ulong)Math.Min((long)Length, int.MaxValue),
                    (ulong)fileSize - Offset);

                long requestStart = checked((long)Offset);
                bool sequential = h.LastReadEnd >= 0 && requestStart == h.LastReadEnd;
                if (sequential) h.SequentialReadCount = Math.Min(h.SequentialReadCount + 1, 16);
                else h.SequentialReadCount = 0;

                if (h.ReadSession != null && h.ReadSession.Position != requestStart &&
                    !(h.ReadCache != null && requestStart >= h.ReadCacheOffset && requestStart < h.ReadCacheOffset + h.ReadCache.Length))
                    CloseReadSession(h);

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
                        byte[] shared;
                        if (_sharedReadCache.TryGet(h.RemotePath, h.Entry.ModifiedUtc, position, remainingWanted, out shared))
                        {
                            h.ReadCache = shared;
                            h.ReadCacheOffset = position;
                        }
                        else
                        {
                            bool continueSession = h.ReadSession != null && h.ReadSession.Position == position;
                            bool preferSession = continueSession || h.SequentialReadCount >= 1 || remainingWanted >= MidReadBlockSize;

                            long fetchOffset = position;
                            int blockLength;
                            if (!preferSession && remainingWanted < MidReadBlockSize)
                            {
                                // 탐색기/Property Handler의 MP3 태그 읽기는 앞/뒤에서 작은 랜덤 읽기를 반복한다.
                                // 64 KiB 경계로 정렬해 동일 영역을 여러 핸들이 다시 요청해도 공용 캐시가 맞는다.
                                fetchOffset = (position / SharedReadCache.MaxBlockBytes) * SharedReadCache.MaxBlockBytes;
                                blockLength = (int)Math.Min((long)SharedReadCache.MaxBlockBytes, fileSize - fetchOffset);
                            }
                            else
                            {
                                blockLength = Math.Max(remainingWanted, prefetch);
                                blockLength = (int)Math.Min((long)blockLength, fileSize - position);
                            }
                            if (blockLength <= 0) break;

                            if (preferSession && !continueSession)
                            {
                                CloseReadSession(h);
                                h.ReadSession = _ftp.TryOpenReadSession(h.RemotePath, position);
                                continueSession = h.ReadSession != null;
                            }

                            if (continueSession)
                            {
                                h.ReadCache = h.ReadSession.ReadUpTo(blockLength);
                                h.ReadCacheOffset = position;
                            }
                            else
                            {
                                byte[] alignedShared;
                                if (_sharedReadCache.TryGet(h.RemotePath, h.Entry.ModifiedUtc, fetchOffset,
                                    (int)Math.Min((long)blockLength, fileSize - fetchOffset), out alignedShared))
                                {
                                    h.ReadCache = alignedShared;
                                }
                                else
                                {
                                    h.ReadCache = _ftp.ReadRange(h.RemotePath, fetchOffset, blockLength);
                                    if (h.ReadCache != null && h.ReadCache.Length > 0 &&
                                        h.ReadCache.Length <= SharedReadCache.MaxBlockBytes)
                                        _sharedReadCache.Put(h.RemotePath, h.Entry.ModifiedUtc, fetchOffset, h.ReadCache);
                                }
                                h.ReadCacheOffset = fetchOffset;
                            }

                            if (h.ReadCache == null || h.ReadCache.Length == 0) break;
                        }
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

                if (h.ReadSession != null && h.ReadSession.Position >= fileSize)
                    CloseReadSession(h);

                // Do not let hundreds of Explorer property handles each pin another 64 KiB block.
                // Random metadata blocks remain in the bounded 16 MiB shared LRU cache instead.
                if (h.ReadSession == null && h.ReadCache != null &&
                    (h.ReadCache.Length <= SharedReadCache.MaxBlockBytes || h.SequentialReadCount == 0))
                {
                    h.ReadCache = null;
                    h.ReadCacheOffset = -1;
                }

                return STATUS_SUCCESS;
            }
        }

        public override int Write(
            object FileNode, object FileDesc0, IntPtr Buffer, ulong Offset, uint Length, bool WriteToEndOfFile, bool ConstrainedIo,
            out uint PBytesTransferred, out FileInfo FileInfo)
        {
            var h = (FtpHandle)FileDesc0;
            PBytesTransferred = 0;
            if (h.IsZoneStream)
            {
                lock (h)
                {
                    if (WriteToEndOfFile) Offset = (ulong)h.Stream.Length;
                    if (ConstrainedIo)
                    {
                        if (Offset >= (ulong)h.Stream.Length)
                        {
                            FileInfo = MakeZoneStreamFileInfo(h);
                            return STATUS_SUCCESS;
                        }
                        if (Offset + Length > (ulong)h.Stream.Length)
                            Length = (uint)((ulong)h.Stream.Length - Offset);
                    }
                    byte[] data = new byte[(int)Length];
                    Marshal.Copy(Buffer, data, 0, (int)Length);
                    h.Stream.Position = (long)Offset;
                    h.Stream.Write(data, 0, data.Length);
                    PBytesTransferred = Length;
                    FileInfo = MakeZoneStreamFileInfo(h);
                    return STATUS_SUCCESS;
                }
            }
            if (h.IsDirectory)
            {
                FileInfo = default(FileInfo);
                return STATUS_FILE_IS_A_DIRECTORY;
            }
            if (h.IsPendingReader)
            {
                FileInfo = MakeFileInfo(h);
                return STATUS_ACCESS_DENIED;
            }

            lock (h)
            {
                EnsureLocal(h, createEmpty: false);
                if (WriteToEndOfFile) Offset = (ulong)h.Stream.Length;
                if (ConstrainedIo)
                {
                    if (Offset >= (ulong)h.Stream.Length)
                    {
                        FileInfo = MakeFileInfo(h);
                        return STATUS_SUCCESS;
                    }
                    if (Offset + Length > (ulong)h.Stream.Length)
                        Length = (uint)((ulong)h.Stream.Length - Offset);
                }

                byte[] tmp = new byte[(int)Length];
                Marshal.Copy(Buffer, tmp, 0, (int)Length);
                h.Stream.Position = (long)Offset;
                h.Stream.Write(tmp, 0, tmp.Length);

                if (h.StreamWriteEligible && Length > 0)
                {
                    try
                    {
                        if (h.WriteSession == null)
                        {
                            if (Offset == 0)
                                h.WriteSession = _ftp.TryOpenUploadSession(h.RemotePath);
                            if (h.WriteSession == null)
                                h.StreamWriteEligible = false;
                        }

                        if (h.WriteSession != null)
                        {
                            if ((long)Offset != h.WriteSession.Position)
                            {
                                CloseWriteSession(h);
                                h.StreamWriteEligible = false;
                            }
                            else
                            {
                                h.WriteSession.Write(tmp, 0, tmp.Length);
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        ExceptionHandler(ex);
                        CloseWriteSession(h);
                        h.StreamWriteEligible = false;
                    }
                }

                h.Dirty = true;
                h.Entry.Size = h.Stream.Length;
                h.Entry.ModifiedUtc = DateTime.UtcNow;
                PBytesTransferred = Length;
                FileInfo = MakeFileInfo(h);
                return STATUS_SUCCESS;
            }
        }

        public override int Flush(object FileNode, object FileDesc0, out FileInfo FileInfo)
        {
            var h = FileDesc0 as FtpHandle;
            if (h == null)
            {
                FileInfo = default(FileInfo);
                return STATUS_SUCCESS;
            }
            if (h.IsZoneStream)
            {
                lock (h)
                {
                    h.Stream?.Flush(true);
                    FileInfo = MakeZoneStreamFileInfo(h);
                    return STATUS_SUCCESS;
                }
            }
            lock (h)
            {
                if (h.Stream != null) h.Stream.Flush(true);
                if (h.Dirty && !h.IsDirectory && !h.DeletePending)
                {
                    if (h.WriteSession != null)
                    {
                        h.WriteSession.Flush();
                        if (h.ExpectedWriteSize > 0 && h.WriteSession.Position >= h.ExpectedWriteSize)
                            FinishStreamingUpload(h);
                    }
                    else
                    {
                        UploadDirty(h);
                    }
                }
                FileInfo = MakeFileInfo(h);
                return STATUS_SUCCESS;
            }
        }

        public override int GetFileInfo(object FileNode, object FileDesc0, out FileInfo FileInfo)
        {
            var h = (FtpHandle)FileDesc0;
            if (h.IsZoneStream)
            {
                lock (h)
                {
                    FileInfo = MakeZoneStreamFileInfo(h);
                    return STATUS_SUCCESS;
                }
            }
            lock (h)
            {
                FileInfo = MakeFileInfo(h);
                return STATUS_SUCCESS;
            }
        }

        public override int SetBasicInfo(
            object FileNode, object FileDesc0, uint FileAttributes, ulong CreationTime, ulong LastAccessTime, ulong LastWriteTime, ulong ChangeTime,
            out FileInfo FileInfo)
        {
            // FTP 표준에는 Windows 속성/생성시각을 동일하게 보존하는 기능이 없다.
            // 복사 작업이 실패하지 않도록 성공 처리하고 실제 데이터만 보존한다.
            var h = (FtpHandle)FileDesc0;
            FileInfo = h.IsZoneStream ? MakeZoneStreamFileInfo(h) : MakeFileInfo(h);
            return STATUS_SUCCESS;
        }

        public override int SetFileSize(object FileNode, object FileDesc0, ulong NewSize, bool SetAllocationSize, out FileInfo FileInfo)
        {
            var h = (FtpHandle)FileDesc0;
            if (h.IsZoneStream)
            {
                lock (h)
                {
                    long requested = (long)Math.Min(NewSize, (ulong)long.MaxValue);
                    if (!SetAllocationSize || requested < h.Stream.Length) h.Stream.SetLength(requested);
                    FileInfo = MakeZoneStreamFileInfo(h);
                    return STATUS_SUCCESS;
                }
            }
            if (h.IsDirectory)
            {
                FileInfo = default(FileInfo);
                return STATUS_FILE_IS_A_DIRECTORY;
            }
            if (h.IsPendingReader)
            {
                FileInfo = MakeFileInfo(h);
                return STATUS_ACCESS_DENIED;
            }
            lock (h)
            {
                EnsureLocal(h, createEmpty: false);
                long requested = (long)Math.Min(NewSize, (ulong)long.MaxValue);
                if (!SetAllocationSize) h.ExpectedWriteSize = requested;

                if (h.WriteSession != null && !SetAllocationSize && requested < h.WriteSession.Position)
                {
                    CloseWriteSession(h);
                    h.StreamWriteEligible = false;
                }

                if (!SetAllocationSize || NewSize < (ulong)h.Stream.Length)
                {
                    h.Stream.SetLength(requested);
                    h.Dirty = true;
                    h.Entry.Size = h.Stream.Length;
                    h.Entry.ModifiedUtc = DateTime.UtcNow;
                }
                FileInfo = MakeFileInfo(h);
                return STATUS_SUCCESS;
            }
        }

        public override int CanDelete(object FileNode, object FileDesc0, string FileName)
        {
            var h = (FtpHandle)FileDesc0;
            if (h.IsZoneStream) return STATUS_SUCCESS;
            if (h.IsDirectory && h.RemotePath != "/")
            {
                List<RemoteEntry> list = _ftp.List(h.RemotePath);
                if (list.Count != 0) return STATUS_DIRECTORY_NOT_EMPTY;
            }
            if (h.RemotePath == "/") return STATUS_ACCESS_DENIED;
            return STATUS_SUCCESS;
        }

        public override int Rename(object FileNode, object FileDesc0, string FileName, string NewFileName, bool ReplaceIfExists)
        {
            var h = (FtpHandle)FileDesc0;
            if (h.IsZoneStream) return STATUS_INVALID_DEVICE_REQUEST;
            string oldPath = h.RemotePath;
            string newPath = ToRemotePath(StripDefaultDataStream(NewFileName));
            if (string.Equals(oldPath, newPath, StringComparison.Ordinal))
                return STATUS_SUCCESS;

            RemoteEntry exists = _ftp.Stat(newPath);
            if (exists != null && !ReplaceIfExists)
                return STATUS_OBJECT_NAME_COLLISION;

            // 아직 원격에 없는 새 파일은 경로만 바꾸고 Close/Flush 시 원자적으로 업로드한다.
            if (!h.Uploaded)
            {
                if (h.WriteSession != null) h.WriteSession.SetFinalPath(newPath);
                MovePending(h, oldPath, newPath);
                MoveZoneMetadata(oldPath, newPath, h.IsDirectory);
                h.RemotePath = newPath;
                h.Entry.FullPath = newPath;
                h.Entry.Name = LightweightFtpClient.LeafName(newPath);
                return STATUS_SUCCESS;
            }

            if (h.WriteSession != null) h.WriteSession.SetFinalPath(newPath);

            string backup = null;
            bool backupMade = false;
            try
            {
                if (exists != null)
                {
                    backup = newPath + ".yongbonge-backup-" + Guid.NewGuid().ToString("N");
                    _ftp.Rename(newPath, backup);
                    backupMade = true;
                }

                _ftp.Rename(oldPath, newPath);
                if (backupMade)
                {
                    try
                    {
                        if (exists.IsDirectory) _ftp.RemoveDirectory(backup);
                        else _ftp.DeleteFile(backup);
                    }
                    catch { }
                }
            }
            catch
            {
                if (backupMade)
                {
                    try { _ftp.Rename(backup, newPath); } catch { }
                }
                throw;
            }

            _sharedReadCache.Invalidate(oldPath);
            _sharedReadCache.Invalidate(newPath);
            MoveZoneMetadata(oldPath, newPath, h.IsDirectory);
            h.RemotePath = newPath;
            h.Entry.FullPath = newPath;
            h.Entry.Name = LightweightFtpClient.LeafName(newPath);
            return STATUS_SUCCESS;
        }

        public override int GetSecurity(object FileNode, object FileDesc0, ref byte[] SecurityDescriptor)
        {
            SecurityDescriptor = null;
            return STATUS_SUCCESS;
        }

        public override int SetSecurity(object FileNode, object FileDesc0, AccessControlSections Sections, byte[] SecurityDescriptor)
        {
            return STATUS_SUCCESS;
        }

        public override bool GetStreamEntry(
            object FileNode, object FileDesc0, ref object Context,
            out string StreamName, out ulong StreamSize, out ulong StreamAllocationSize)
        {
            StreamName = null;
            StreamSize = 0;
            StreamAllocationSize = 0;
            Context = null;
            return false;
        }

        public override int ReadDirectory(
            object FileNode, object FileDesc0, string Pattern, string Marker,
            IntPtr Buffer, uint Length, out uint BytesTransferred)
        {
            var h = FileDesc0 as FtpHandle;
            if (h == null || !h.IsDirectory)
            {
                BytesTransferred = 0;
                TraceLog.Write("CB", "ReadDirectory NOT_DIR");
                return STATUS_NOT_A_DIRECTORY;
            }

            TraceLog.Write("CB", "ReadDirectory BEGIN path=" + h.RemotePath + " marker=" + (Marker ?? "<null>") +
                " pattern=" + (Pattern ?? "<null>") + " buf=" + Length);

            if (h.DirectoryBuffer == null)
            {
                lock (h)
                {
                    if (h.DirectoryBuffer == null)
                    {
                        h.DirectoryBuffer = new DirectoryBuffer();
                        TraceLog.Write("CB", "ReadDirectory NEW_BUFFER path=" + h.RemotePath);
                    }
                }
            }

            Stopwatch sw = Stopwatch.StartNew();
            int status = BufferedReadDirectory(
                h.DirectoryBuffer, FileNode, FileDesc0, Pattern, Marker,
                Buffer, Length, out BytesTransferred);
            TraceLog.Write("CB", "ReadDirectory END path=" + h.RemotePath + " status=0x" +
                unchecked((uint)status).ToString("X8") + " bytes=" + BytesTransferred +
                " ms=" + sw.Elapsed.TotalMilliseconds.ToString("0.0", System.Globalization.CultureInfo.InvariantCulture));
            return status;
        }

        public override bool ReadDirectoryEntry(
            object FileNode, object FileDesc0, string Pattern, string Marker, ref object Context,
            out string FileName, out FileInfo FileInfo)
        {
            var h = (FtpHandle)FileDesc0;
            if (!h.IsDirectory)
            {
                FileName = null;
                FileInfo = default(FileInfo);
                return false;
            }

            // This Context only lives while WinFsp fills the persistent DirectoryBuffer.
            // The DirectoryBuffer itself survives subsequent ReadDirectory calls, so large
            // folders do not trigger another FTP LIST every time the output buffer fills.
            DirectoryEnumerationState state = Context as DirectoryEnumerationState;
            if (state == null)
            {
                TraceLog.Write("ENUM", "FETCH_BEGIN path=" + h.RemotePath + " marker=" + (Marker ?? "<null>"));
                Stopwatch enumSw = Stopwatch.StartNew();
                List<RemoteEntry> entries = _ftp.List(h.RemotePath);
                entries.Sort((a, b) => StringComparer.OrdinalIgnoreCase.Compare(a.Name, b.Name));
                TraceLog.Write("ENUM", "FETCH_END path=" + h.RemotePath + " entries=" + entries.Count +
                    " ms=" + enumSw.Elapsed.TotalMilliseconds.ToString("0.0", System.Globalization.CultureInfo.InvariantCulture));

                int index = 0;
                if (!string.IsNullOrEmpty(Marker))
                {
                    while (index < entries.Count &&
                        StringComparer.OrdinalIgnoreCase.Compare(entries[index].Name, Marker) <= 0)
                        index++;
                }

                state = new DirectoryEnumerationState { Entries = entries, Index = index };
                Context = state;
            }

            if (state.Index >= state.Entries.Count)
            {
                TraceLog.Write("ENUM", "END path=" + h.RemotePath + " total=" + state.Entries.Count);
                FileName = null;
                FileInfo = default(FileInfo);
                return false;
            }

            RemoteEntry e = state.Entries[state.Index++];
            if (state.Index <= 5 || state.Index % 25 == 0 || state.Index == state.Entries.Count)
                TraceLog.Write("ENUM", "RETURN path=" + h.RemotePath + " idx=" + state.Index + "/" + state.Entries.Count + " name=" + e.Name);
            FileName = e.Name;
            FileInfo = MakeFileInfo(e);
            return true;
        }

        private bool TryGetPending(string path, out FtpHandle handle)
        {
            handle = null;
            if (string.IsNullOrEmpty(path)) return false;
            lock (_pendingSync)
            {
                FtpHandle candidate;
                if (!_pendingFiles.TryGetValue(path, out candidate) || candidate == null || candidate.DeletePending)
                    return false;
                handle = candidate;
                return true;
            }
        }

        private void RegisterPending(FtpHandle handle)
        {
            if (handle == null || handle.IsDirectory || string.IsNullOrEmpty(handle.RemotePath)) return;
            lock (_pendingSync)
                _pendingFiles[handle.RemotePath] = handle;
        }

        private void UnregisterPending(FtpHandle handle)
        {
            if (handle == null) return;
            lock (_pendingSync)
            {
                var remove = new List<string>();
                foreach (var pair in _pendingFiles)
                    if (object.ReferenceEquals(pair.Value, handle)) remove.Add(pair.Key);
                foreach (string key in remove) _pendingFiles.Remove(key);
            }
        }

        private void MovePending(FtpHandle handle, string oldPath, string newPath)
        {
            if (handle == null || string.IsNullOrEmpty(newPath)) return;
            lock (_pendingSync)
            {
                FtpHandle current;
                if (!string.IsNullOrEmpty(oldPath) &&
                    _pendingFiles.TryGetValue(oldPath, out current) &&
                    object.ReferenceEquals(current, handle))
                    _pendingFiles.Remove(oldPath);
                _pendingFiles[newPath] = handle;
            }
        }

        private FtpHandle OpenPendingReadHandle(FtpHandle owner)
        {
            if (owner == null) return null;
            lock (owner)
            {
                if (owner.DeletePending || owner.IsDirectory ||
                    string.IsNullOrEmpty(owner.TempPath) || !File.Exists(owner.TempPath))
                    return null;

                FileStream readStream;
                try
                {
                    readStream = new FileStream(
                        owner.TempPath, FileMode.Open, FileAccess.Read,
                        FileShare.ReadWrite | FileShare.Delete, 65536, FileOptions.RandomAccess);
                }
                catch
                {
                    return null;
                }

                long size = 0;
                try { size = readStream.Length; } catch { }
                return new FtpHandle
                {
                    RemotePath = owner.RemotePath,
                    IsDirectory = false,
                    Entry = new RemoteEntry
                    {
                        Name = LightweightFtpClient.LeafName(owner.RemotePath),
                        FullPath = owner.RemotePath,
                        IsDirectory = false,
                        Size = size,
                        ModifiedUtc = owner.Entry == null ? DateTime.UtcNow : owner.Entry.ModifiedUtc
                    },
                    TempPath = owner.TempPath,
                    Stream = readStream,
                    LocalReady = true,
                    Dirty = false,
                    Created = false,
                    Uploaded = false,
                    IsPendingReader = true
                };
            }
        }

        private static void CloseWriteSession(FtpHandle h)
        {
            if (h == null || h.WriteSession == null) return;
            try { h.WriteSession.Dispose(); } catch { }
            h.WriteSession = null;
        }

        private void FinishStreamingUpload(FtpHandle h)
        {
            if (h == null || h.WriteSession == null) return;
            LightweightFtpClient.UploadSession session = h.WriteSession;
            h.WriteSession = null;
            session.SetFinalPath(h.RemotePath);
            session.Finish();
            h.Dirty = false;
            h.Uploaded = true;
            h.Created = false;
            h.StreamWriteEligible = false;
            h.Entry.Size = session.Position;
            h.Entry.ModifiedUtc = DateTime.UtcNow;
            _sharedReadCache.Invalidate(h.RemotePath);
            UnregisterPending(h);
        }

        private static void CloseReadSession(FtpHandle h)
        {
            if (h == null || h.ReadSession == null) return;
            try { h.ReadSession.Dispose(); } catch { }
            h.ReadSession = null;
        }

        private static int AdaptiveReadSize(int sequentialCount)
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
        {
            CloseReadSession(h);
            if (h.LocalReady)
            {
                if (h.Stream == null)
                    h.Stream = OpenTemp(h.TempPath);
                return;
            }

            h.TempPath = Path.Combine(_cacheDir, Guid.NewGuid().ToString("N") + ".tmp");
            if (createEmpty || h.Created)
            {
                using (File.Create(h.TempPath)) { }
            }
            else
            {
                _ftp.Download(h.RemotePath, h.TempPath);
            }
            h.Stream = OpenTemp(h.TempPath);
            h.LocalReady = true;
        }

        private static FileStream OpenTemp(string path)
        {
            return new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.Read, 65536, FileOptions.RandomAccess);
        }

        private void UploadDirty(FtpHandle h)
        {
            CloseWriteSession(h);
            h.StreamWriteEligible = false;
            if (h.Stream != null) h.Stream.Flush(true);
            _ftp.UploadAtomic(h.TempPath, h.RemotePath);
            _sharedReadCache.Invalidate(h.RemotePath);
            h.Dirty = false;
            h.Uploaded = true;
            h.Created = false;
            h.Entry.Size = h.Stream?.Length ?? new System.IO.FileInfo(h.TempPath).Length;
            h.Entry.ModifiedUtc = DateTime.UtcNow;
            UnregisterPending(h);
        }

        private static bool TryGetNamedStream(string fileName, out string baseName, out string streamName)
        {
            baseName = fileName;
            streamName = null;
            if (string.IsNullOrEmpty(fileName)) return false;

            int slash = fileName.LastIndexOf('\\');
            int colon = fileName.IndexOf(':', slash + 1);
            if (colon < 0) return false;

            string suffix = fileName.Substring(colon + 1);
            int typeColon = suffix.IndexOf(':');
            string name = typeColon >= 0 ? suffix.Substring(0, typeColon) : suffix;

            // file::$DATA는 기본 데이터 스트림 표기이므로 일반 파일로 취급한다.
            if (name.Length == 0)
            {
                baseName = fileName.Substring(0, colon);
                return false;
            }

            baseName = fileName.Substring(0, colon);
            streamName = name;
            return true;
        }

        private static string StripDefaultDataStream(string fileName)
        {
            if (string.IsNullOrEmpty(fileName)) return fileName;
            const string suffix = "::$DATA";
            if (fileName.EndsWith(suffix, StringComparison.OrdinalIgnoreCase))
                return fileName.Substring(0, fileName.Length - suffix.Length);
            return fileName;
        }

        private static bool IsZoneStreamName(string streamName)
        {
            return false;
        }

        private FtpHandle OpenZoneStreamHandle(string baseName, bool create)
        {
            string remotePath = ToRemotePath(baseName);
            string sidecar = ZoneSidecarPath(remotePath);
            FileStream stream;
            lock (_adsSync)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(sidecar));
                FileMode mode = create ? FileMode.Create : FileMode.Open;
                stream = new FileStream(sidecar, mode, FileAccess.ReadWrite, FileShare.ReadWrite, 4096, FileOptions.RandomAccess);
            }
            return new FtpHandle
            {
                RemotePath = remotePath,
                IsDirectory = false,
                IsZoneStream = true,
                TempPath = sidecar,
                Stream = stream,
                LocalReady = true,
                Uploaded = true,
                Entry = new RemoteEntry
                {
                    Name = LightweightFtpClient.LeafName(remotePath),
                    FullPath = remotePath,
                    IsDirectory = false,
                    Size = stream.Length,
                    ModifiedUtc = DateTime.UtcNow
                }
            };
        }

        private FileInfo MakeZoneStreamFileInfo(FtpHandle h)
        {
            ulong size = (ulong)Math.Max(0, h.Stream == null ? 0 : h.Stream.Length);
            ulong time = (ulong)DateTime.UtcNow.ToFileTimeUtc();
            try
            {
                if (!string.IsNullOrEmpty(h.TempPath) && File.Exists(h.TempPath))
                    time = (ulong)File.GetLastWriteTimeUtc(h.TempPath).ToFileTimeUtc();
            }
            catch { }
            return new FileInfo
            {
                FileAttributes = (uint)System.IO.FileAttributes.Archive,
                ReparseTag = 0,
                FileSize = size,
                AllocationSize = ((size + 4095UL) / 4096UL) * 4096UL,
                CreationTime = time,
                LastAccessTime = time,
                LastWriteTime = time,
                ChangeTime = time,
                IndexNumber = 0,
                HardLinks = 0
            };
        }

        private string ZoneSidecarPath(string remotePath)
        {
            string normalized = LightweightFtpClient.NormalizePath(remotePath);
            if (normalized == "/") return Path.Combine(_adsDir, "__root.zone");
            string[] parts = normalized.Trim('/').Split('/');
            string dir = _adsDir;
            for (int i = 0; i < parts.Length - 1; i++) dir = Path.Combine(dir, EncodeAdsComponent(parts[i]));
            return Path.Combine(dir, EncodeAdsComponent(parts[parts.Length - 1]) + ".zone");
        }

        private string ZoneSidecarDirectoryPath(string remotePath)
        {
            string normalized = LightweightFtpClient.NormalizePath(remotePath);
            if (normalized == "/") return _adsDir;
            string dir = _adsDir;
            foreach (string part in normalized.Trim('/').Split('/')) dir = Path.Combine(dir, EncodeAdsComponent(part));
            return dir;
        }

        private static string EncodeAdsComponent(string value)
        {
            string b64 = Convert.ToBase64String(Encoding.UTF8.GetBytes(value ?? ""));
            return b64.TrimEnd('=').Replace('+', '-').Replace('/', '_');
        }

        private void DeleteZoneMetadata(string remotePath, bool isDirectory)
        {
            // ADS disabled in v0.21.
        }

        private void MoveZoneMetadata(string oldPath, string newPath, bool isDirectory)
        {
            // ADS disabled in v0.21.
        }

        private static string ToRemotePath(string winPath)
        {
            if (string.IsNullOrEmpty(winPath) || winPath == "\\") return "/";
            return LightweightFtpClient.NormalizePath(winPath);
        }

        private static RemoteEntry RootEntry()
        {
            return new RemoteEntry { Name = "", FullPath = "/", IsDirectory = true, Size = 0, ModifiedUtc = DateTime.UtcNow };
        }

        private static uint EntryAttributes(RemoteEntry e)
        {
            return e.IsDirectory ? (uint)System.IO.FileAttributes.Directory : (uint)System.IO.FileAttributes.Archive;
        }

        private static FileInfo MakeFileInfo(FtpHandle h)
        {
            RemoteEntry e = h.Entry;
            if (!h.IsDirectory && h.LocalReady && h.Stream != null)
            {
                e.Size = h.Stream.Length;
                if (h.Dirty) e.ModifiedUtc = DateTime.UtcNow;
            }
            return MakeFileInfo(e);
        }

        private static FileInfo MakeFileInfo(RemoteEntry e)
        {
            ulong size = e.IsDirectory ? 0UL : (ulong)Math.Max(0, e.Size);
            ulong time;
            try { time = (ulong)e.ModifiedUtc.ToUniversalTime().ToFileTimeUtc(); }
            catch { time = (ulong)DateTime.UtcNow.ToFileTimeUtc(); }

            return new FileInfo
            {
                FileAttributes = EntryAttributes(e),
                ReparseTag = 0,
                FileSize = size,
                AllocationSize = ((size + 4095UL) / 4096UL) * 4096UL,
                CreationTime = time,
                LastAccessTime = time,
                LastWriteTime = time,
                ChangeTime = time,
                IndexNumber = 0,
                HardLinks = 0
            };
        }
    }
}
