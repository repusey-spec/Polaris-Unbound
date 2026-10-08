using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace TinyFtpDrive
{
    internal sealed class RemoteEntry
    {
        public string Name;
        public string FullPath;
        public bool IsDirectory;
        public long Size;
        public DateTime ModifiedUtc;
    }

    internal sealed class FtpException : IOException
    {
        public int Code { get; }
        public FtpException(int code, string message) : base(message) { Code = code; }
    }

    internal sealed class LightweightFtpClient : IDisposable
    {
        private sealed class FtpResponse
        {
            public int Code;
            public string Text;
        }

        private sealed class CacheItem
        {
            public DateTime LoadedUtc;
            public DateTime ExpiresUtc;
            public List<RemoteEntry> Entries;
            public Dictionary<string, RemoteEntry> Index;
            public long EstimatedBytes;
        }

        internal sealed class ReadSession : IDisposable
        {
            private readonly LightweightFtpClient _owner;
            private LightweightFtpClient _client;
            private TcpClient _data;
            private NetworkStream _stream;
            private bool _disposed;
            private bool _releasedSlot;

            public long Position { get; private set; }

            internal ReadSession(LightweightFtpClient owner, string remotePath, long offset)
            {
                _owner = owner;
                _client = new LightweightFtpClient(owner._server, owner._username, owner._password, false);
                try
                {
                    lock (_client._sync)
                    {
                        _client.EnsureConnectedNoLock();
                        _data = _client.OpenPassiveNoLock();
                        if (offset > 0)
                        {
                            FtpResponse rest = _client.CommandRaw("REST " + offset.ToString(CultureInfo.InvariantCulture));
                            if (rest.Code != 350)
                                throw new FtpException(rest.Code, "FTP 서버가 REST 부분 읽기를 지원하지 않습니다. " + rest.Text);
                        }

                        FtpResponse start = _client.CommandRaw("RETR " + remotePath);
                        if (start.Code != 125 && start.Code != 150)
                            throw new FtpException(start.Code, start.Text);

                        _stream = _data.GetStream();
                        _stream.ReadTimeout = StreamingReadTimeoutMs;
                        Position = offset;
                    }
                }
                catch
                {
                    Dispose();
                    throw;
                }
            }

            public byte[] ReadUpTo(int count)
            {
                if (_disposed) throw new ObjectDisposedException(nameof(ReadSession));
                if (count <= 0) return new byte[0];

                byte[] buffer = new byte[count];
                int total = 0;
                try
                {
                    while (total < count)
                    {
                        int n = _stream.Read(buffer, total, count - total);
                        if (n <= 0)
                        {
                            try
                            {
                                lock (_client._sync)
                                    Require(_client.ReadResponse(), 226, 250);
                            }
                            catch { }
                            break;
                        }
                        total += n;
                        Position += n;
                    }
                }
                catch
                {
                    Dispose();
                    throw;
                }

                if (total == buffer.Length) return buffer;
                byte[] trimmed = new byte[total];
                if (total > 0) System.Buffer.BlockCopy(buffer, 0, trimmed, 0, total);
                return trimmed;
            }

            public void Dispose()
            {
                if (_disposed) return;
                _disposed = true;
                try { _stream?.Dispose(); } catch { }
                try { _data?.Close(); } catch { }
                _stream = null;
                _data = null;
                try { _client?.Dispose(); } catch { }
                _client = null;
                _owner.UnregisterReadSession(this);
                ReleaseSlot();
            }

            private void ReleaseSlot()
            {
                if (_releasedSlot) return;
                _releasedSlot = true;
                try { _owner._persistentReadSlots?.Release(); } catch { }
            }
        }

        internal sealed class UploadSession : IDisposable
        {
            private readonly LightweightFtpClient _owner;
            private LightweightFtpClient _client;
            private TcpClient _data;
            private NetworkStream _stream;
            private string _finalPath;
            private readonly string _tempPath;
            private bool _finished;
            private bool _disposed;
            private bool _releasedSlot;

            public long Position { get; private set; }

            internal UploadSession(LightweightFtpClient owner, string remotePath)
            {
                _owner = owner;
                _finalPath = NormalizePath(remotePath);
                string parent = ParentPath(_finalPath);
                _tempPath = CombinePath(parent, ".yongbonge-stream-" + Guid.NewGuid().ToString("N") + ".tmp");
                _client = new LightweightFtpClient(owner._server, owner._username, owner._password, false);

                try
                {
                    lock (_client._sync)
                    {
                        _client.EnsureConnectedNoLock();
                        _data = _client.OpenPassiveNoLock();
                        FtpResponse start = _client.CommandRaw("STOR " + _tempPath);
                        if (start.Code != 125 && start.Code != 150)
                            throw new FtpException(start.Code, start.Text);
                        _stream = _data.GetStream();
                        _stream.WriteTimeout = StreamingWriteTimeoutMs;
                    }
                }
                catch
                {
                    Dispose();
                    throw;
                }
            }

            public void SetFinalPath(string remotePath)
            {
                if (_finished || _disposed) return;
                _finalPath = NormalizePath(remotePath);
            }

            public void Write(byte[] buffer, int offset, int count)
            {
                if (_disposed) throw new ObjectDisposedException(nameof(UploadSession));
                if (_finished) throw new InvalidOperationException("업로드 세션이 이미 완료되었습니다.");
                if (count <= 0) return;
                try
                {
                    _stream.Write(buffer, offset, count);
                    Position += count;
                }
                catch
                {
                    Dispose();
                    throw;
                }
            }

            public void Flush()
            {
                if (_disposed || _finished) return;
                _stream.Flush();
            }

            public void Finish()
            {
                if (_finished) return;
                if (_disposed) throw new ObjectDisposedException(nameof(UploadSession));

                try
                {
                    try { _stream?.Flush(); } catch { }
                    try { _stream?.Dispose(); } catch { }
                    try { _data?.Close(); } catch { }
                    _stream = null;
                    _data = null;

                    lock (_client._sync)
                    {
                        Require(_client.ReadResponse(), 226, 250);

                        string parent = ParentPath(_finalPath);
                        string token = Guid.NewGuid().ToString("N");
                        string backupPath = CombinePath(parent, ".yongbonge-stream-" + token + ".bak");
                        bool backupMade = false;
                        try
                        {
                            FtpResponse exists = _client.CommandRaw("RNFR " + _finalPath);
                            if (exists.Code == 350)
                            {
                                Require(_client.CommandRaw("RNTO " + backupPath), 250);
                                backupMade = true;
                            }
                            else if (exists.Code != 550)
                                throw new FtpException(exists.Code, exists.Text);

                            Require(_client.CommandRaw("RNFR " + _tempPath), 350);
                            Require(_client.CommandRaw("RNTO " + _finalPath), 250);

                            if (backupMade)
                            {
                                try { Require(_client.CommandRaw("DELE " + backupPath), 250); }
                                catch { }
                            }
                        }
                        catch
                        {
                            if (backupMade)
                            {
                                try
                                {
                                    _client.CommandRaw("RNFR " + backupPath);
                                    _client.CommandRaw("RNTO " + _finalPath);
                                }
                                catch { }
                            }
                            throw;
                        }
                    }

                    _finished = true;
                    lock (_owner._sync) _owner.InvalidateNoLock(_finalPath);
                    DisposeCore(false);
                }
                catch
                {
                    Dispose();
                    throw;
                }
            }

            public void Dispose()
            {
                if (_disposed) return;
                DisposeCore(!_finished);
            }

            private void DisposeCore(bool deleteTemp)
            {
                if (_disposed) return;
                _disposed = true;
                try { _stream?.Dispose(); } catch { }
                try { _data?.Close(); } catch { }
                _stream = null;
                _data = null;
                try { _client?.Dispose(); } catch { }
                _client = null;

                if (deleteTemp && !_owner._disposed)
                {
                    try { _owner.DeleteFile(_tempPath); } catch { }
                }

                _owner.UnregisterUploadSession(this);
                if (!_releasedSlot)
                {
                    _releasedSlot = true;
                    try { _owner._persistentWriteSlots?.Release(); } catch { }
                }
            }
        }

        private const int MaxMetadataReadSessions = 4;
        private const int MaxStreamingReadSessions = 2;
        private const int MaxStreamingWriteSessions = 2;

        // Folder listing is latency-critical. Never let a dead request queue for tens of seconds.
        private const int ConnectTimeoutMs = 1200;
        private const int ControlTimeoutMs = 1500;
        private const int DirectoryFirstDataTimeoutMs = 1200;
        private const int DirectoryTotalTimeoutMs = 2000;
        private const int MetadataReadTimeoutMs = 1200;
        private const int MetadataSlotWaitTimeoutMs = 250;
        private const int StreamingReadTimeoutMs = 8000;
        private const int StreamingWriteTimeoutMs = 8000;

        private const long MaxDirectoryCacheBytes = 4L * 1024 * 1024;
        private static readonly TimeSpan DirectoryCacheLifetime = TimeSpan.FromSeconds(30);

        private readonly object _sync = new object();
        private readonly string _server;
        private readonly string _username;
        private readonly string _password;
        private readonly Dictionary<string, CacheItem> _dirCache = new Dictionary<string, CacheItem>(StringComparer.OrdinalIgnoreCase);
        private long _dirCacheBytes;
        private readonly Encoding _commandEncoding = new UTF8Encoding(false, false);
        private readonly Encoding _strictUtf8 = new UTF8Encoding(false, true);
        private readonly bool _enableReadPool;
        private readonly SemaphoreSlim _readSlots;
        private readonly SemaphoreSlim _persistentReadSlots;
        private readonly SemaphoreSlim _persistentWriteSlots;
        private readonly ConcurrentQueue<LightweightFtpClient> _rangeWorkers = new ConcurrentQueue<LightweightFtpClient>();
        private readonly object _readSessionSync = new object();
        private readonly HashSet<ReadSession> _activeReadSessions = new HashSet<ReadSession>();
        private readonly object _writeSessionSync = new object();
        private readonly HashSet<UploadSession> _activeUploadSessions = new HashSet<UploadSession>();
        private bool _disposed;

        private TcpClient _control;
        private NetworkStream _controlStream;
        private StreamReader _reader;
        private StreamWriter _writer;
        private string _host;
        private int _port;

        public LightweightFtpClient(AppConfig cfg)
            : this(cfg.Server, cfg.Username, cfg.Password ?? "", true)
        {
        }

        private LightweightFtpClient(string server, string username, string password, bool enableReadPool)
        {
            _server = server;
            _username = username;
            _password = password ?? "";
            _enableReadPool = enableReadPool;
            ParseServer(_server, out _host, out _port);

            if (_enableReadPool)
            {
                _readSlots = new SemaphoreSlim(MaxMetadataReadSessions, MaxMetadataReadSessions);
                _persistentReadSlots = new SemaphoreSlim(MaxStreamingReadSessions, MaxStreamingReadSessions);
                _persistentWriteSlots = new SemaphoreSlim(MaxStreamingWriteSessions, MaxStreamingWriteSessions);
            }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            lock (_sync) DisconnectNoLock();

            if (_enableReadPool)
            {
                ReadSession[] sessions;
                lock (_readSessionSync) sessions = new List<ReadSession>(_activeReadSessions).ToArray();
                foreach (ReadSession session in sessions)
                {
                    try { session.Dispose(); } catch { }
                }
                UploadSession[] uploads;
                lock (_writeSessionSync) uploads = new List<UploadSession>(_activeUploadSessions).ToArray();
                foreach (UploadSession session in uploads)
                {
                    try { session.Dispose(); } catch { }
                }
                DisposeRangeWorkers();
            }
        }

        public void ResetConnection()
        {
            lock (_sync)
            {
                DisconnectNoLock();
                _dirCache.Clear();
                _dirCacheBytes = 0;
            }

            if (_enableReadPool)
            {
                ReadSession[] sessions;
                lock (_readSessionSync) sessions = new List<ReadSession>(_activeReadSessions).ToArray();
                foreach (ReadSession session in sessions)
                {
                    try { session.Dispose(); } catch { }
                }
                UploadSession[] uploads;
                lock (_writeSessionSync) uploads = new List<UploadSession>(_activeUploadSessions).ToArray();
                foreach (UploadSession session in uploads)
                {
                    try { session.Dispose(); } catch { }
                }
                DisposeRangeWorkers();
            }
        }

        public RemoteEntry Stat(string path)
        {
            path = NormalizePath(path);
            if (path == "/")
                return new RemoteEntry { Name = "", FullPath = "/", IsDirectory = true, Size = 0, ModifiedUtc = DateTime.UtcNow };

            string parent = ParentPath(path);
            string leaf = LeafName(path);
            lock (_sync)
            {
                CacheItem cache = GetDirectoryCacheNoLock(parent);
                RemoteEntry entry;
                if (cache.Index.TryGetValue(leaf, out entry))
                    return CloneEntry(entry);
                return null;
            }
        }

        public List<RemoteEntry> List(string path)
        {
            path = NormalizePath(path);
            lock (_sync)
                return CloneEntries(GetDirectoryCacheNoLock(path).Entries);
        }

        private CacheItem GetDirectoryCacheNoLock(string path)
        {
            CacheItem cached;
            bool hadCached = _dirCache.TryGetValue(path, out cached);
            if (hadCached && cached.ExpiresUtc > DateTime.UtcNow)
                return cached;

            try
            {
                // Directory retry count is deliberately zero. A stale snapshot is preferable to
                // blocking Explorer while a dead FTP request retries.
                EnsureConnectedNoLock();
                List<RemoteEntry> result = ListNoLock(path);
                var index = new Dictionary<string, RemoteEntry>(StringComparer.OrdinalIgnoreCase);
                long estimated = 256;
                foreach (RemoteEntry entry in result)
                {
                    if (!index.ContainsKey(entry.Name))
                        index.Add(entry.Name, entry);
                    estimated += 128L
                        + ((entry.Name == null ? 0 : entry.Name.Length) * 2L)
                        + ((entry.FullPath == null ? 0 : entry.FullPath.Length) * 2L);
                }

                CacheItem fresh = new CacheItem
                {
                    LoadedUtc = DateTime.UtcNow,
                    ExpiresUtc = DateTime.UtcNow.Add(DirectoryCacheLifetime),
                    Entries = result,
                    Index = index,
                    EstimatedBytes = estimated
                };

                if (hadCached) _dirCacheBytes -= cached.EstimatedBytes;
                _dirCache[path] = fresh;
                _dirCacheBytes += fresh.EstimatedBytes;
                TrimDirectoryCacheNoLock(path);
                return fresh;
            }
            catch (Exception ex) when (IsConnectionFailure(ex))
            {
                // A timed-out MLSD can leave a completion reply pending on the control channel.
                // Drop that session before serving the stale snapshot so the next command starts clean.
                DisconnectNoLock();
                if (hadCached) return cached;
                throw;
            }
        }

        private void TrimDirectoryCacheNoLock(string keepPath)
        {
            while (_dirCacheBytes > MaxDirectoryCacheBytes)
            {
                string oldestKey = null;
                CacheItem oldest = null;
                foreach (KeyValuePair<string, CacheItem> pair in _dirCache)
                {
                    if (string.Equals(pair.Key, keepPath, StringComparison.OrdinalIgnoreCase)) continue;
                    if (oldest == null || pair.Value.LoadedUtc < oldest.LoadedUtc)
                    {
                        oldestKey = pair.Key;
                        oldest = pair.Value;
                    }
                }
                if (oldestKey == null) break;
                RemoveDirectoryCacheItemNoLock(oldestKey);
            }
        }

        private void RemoveDirectoryCacheItemNoLock(string path)
        {
            CacheItem item;
            if (_dirCache.TryGetValue(path, out item))
            {
                _dirCacheBytes -= item.EstimatedBytes;
                if (_dirCacheBytes < 0) _dirCacheBytes = 0;
                _dirCache.Remove(path);
            }
        }

        public void Download(string remotePath, string localPath)
        {
            remotePath = NormalizePath(remotePath);
            lock (_sync)
            {
                RunWithReconnectNoLock(() =>
                {
                    TransferDownloadNoLock(remotePath, localPath);
                    return 0;
                });
            }
        }

        public byte[] ReadRange(string remotePath, long offset, int maxLength)
        {
            remotePath = NormalizePath(remotePath);
            if (offset < 0) throw new ArgumentOutOfRangeException(nameof(offset));
            if (maxLength <= 0) return new byte[0];

            if (!_enableReadPool)
                return ReadRangeReusableDirect(remotePath, offset, maxLength);

            if (!_readSlots.Wait(MetadataSlotWaitTimeoutMs))
                throw new TimeoutException("FTP metadata queue is busy.");
            LightweightFtpClient worker = null;
            bool reusable = false;
            try
            {
                if (!_rangeWorkers.TryDequeue(out worker))
                    worker = new LightweightFtpClient(_server, _username, _password, false);

                byte[] result = worker.ReadRangeReusableDirect(remotePath, offset, maxLength);
                reusable = true;
                return result;
            }
            finally
            {
                if (worker != null)
                {
                    if (reusable && !_disposed) _rangeWorkers.Enqueue(worker);
                    else try { worker.Dispose(); } catch { }
                }
                _readSlots.Release();
            }
        }

        public ReadSession TryOpenReadSession(string remotePath, long offset)
        {
            remotePath = NormalizePath(remotePath);
            if (offset < 0) throw new ArgumentOutOfRangeException(nameof(offset));
            if (!_enableReadPool) throw new InvalidOperationException("읽기 세션은 루트 FTP 클라이언트에서만 열 수 있습니다.");
            if (!_persistentReadSlots.Wait(0)) return null;

            ReadSession session = null;
            try
            {
                session = new ReadSession(this, remotePath, offset);
                lock (_readSessionSync) _activeReadSessions.Add(session);
                return session;
            }
            catch
            {
                if (session == null)
                {
                    try { _persistentReadSlots.Release(); } catch { }
                }
                throw;
            }
        }

        private void UnregisterReadSession(ReadSession session)
        {
            if (!_enableReadPool) return;
            lock (_readSessionSync) _activeReadSessions.Remove(session);
        }

        public UploadSession TryOpenUploadSession(string remotePath)
        {
            remotePath = NormalizePath(remotePath);
            if (!_enableReadPool) throw new InvalidOperationException("쓰기 세션은 루트 FTP 클라이언트에서만 열 수 있습니다.");
            if (!_persistentWriteSlots.Wait(0)) return null;

            UploadSession session = null;
            try
            {
                session = new UploadSession(this, remotePath);
                lock (_writeSessionSync) _activeUploadSessions.Add(session);
                return session;
            }
            catch
            {
                if (session == null)
                {
                    try { _persistentWriteSlots.Release(); } catch { }
                }
                throw;
            }
        }

        private void UnregisterUploadSession(UploadSession session)
        {
            if (!_enableReadPool) return;
            lock (_writeSessionSync) _activeUploadSessions.Remove(session);
        }

        private byte[] ReadRangeReusableDirect(string remotePath, long offset, int maxLength)
        {
            lock (_sync)
            {
                try
                {
                    EnsureConnectedNoLock();
                    return TransferReadRangeReusableNoLock(remotePath, offset, maxLength);
                }
                catch
                {
                    // Metadata retry count is zero. Never return a possibly poisoned control channel.
                    DisconnectNoLock();
                    throw;
                }
            }
        }

        private void DisposeRangeWorkers()
        {
            LightweightFtpClient worker;
            while (_rangeWorkers.TryDequeue(out worker))
            {
                try { worker.Dispose(); } catch { }
            }
        }

        public void Upload(string localPath, string remotePath)
        {
            remotePath = NormalizePath(remotePath);
            lock (_sync)
            {
                RunWithReconnectNoLock(() =>
                {
                    TransferUploadNoLock(localPath, remotePath);
                    InvalidateNoLock(remotePath);
                    return 0;
                });
            }
        }

        public void UploadAtomic(string localPath, string remotePath)
        {
            remotePath = NormalizePath(remotePath);
            string parent = ParentPath(remotePath);
            string token = Guid.NewGuid().ToString("N");
            string tempPath = CombinePath(parent, ".yongbonge-" + token + ".tmp");
            string backupPath = CombinePath(parent, ".yongbonge-" + token + ".bak");

            // 먼저 임시 이름으로 완전히 업로드한다. 이 단계에서 실패해도 기존 파일은 건드리지 않는다.
            Upload(localPath, tempPath);

            lock (_sync)
            {
                EnsureConnectedNoLock();
                bool backupMade = false;
                try
                {
                    FtpResponse exists = CommandRaw("RNFR " + remotePath);
                    if (exists.Code == 350)
                    {
                        Require(CommandRaw("RNTO " + backupPath), 250);
                        backupMade = true;
                    }
                    else if (exists.Code != 550)
                        throw new FtpException(exists.Code, exists.Text);

                    Require(CommandRaw("RNFR " + tempPath), 350);
                    Require(CommandRaw("RNTO " + remotePath), 250);

                    if (backupMade)
                    {
                        try { Require(CommandRaw("DELE " + backupPath), 250); }
                        catch { }
                    }

                    InvalidateNoLock(tempPath);
                    InvalidateNoLock(remotePath);
                }
                catch
                {
                    try { CommandRaw("DELE " + tempPath); } catch { }
                    if (backupMade)
                    {
                        try
                        {
                            CommandRaw("RNFR " + backupPath);
                            CommandRaw("RNTO " + remotePath);
                        }
                        catch { }
                    }
                    DisconnectNoLock();
                    throw;
                }
            }
        }

        public void MakeDirectory(string path)
        {
            path = NormalizePath(path);
            lock (_sync)
            {
                RunWithReconnectNoLock(() =>
                {
                    FtpResponse r = CommandRaw("MKD " + path);
                    Require(r, 257, 250);
                    InvalidateNoLock(path);
                    return 0;
                });
            }
        }

        public void DeleteFile(string path)
        {
            path = NormalizePath(path);
            lock (_sync)
            {
                RunWithReconnectNoLock(() =>
                {
                    FtpResponse r = CommandRaw("DELE " + path);
                    Require(r, 250);
                    InvalidateNoLock(path);
                    return 0;
                });
            }
        }

        public void RemoveDirectory(string path)
        {
            path = NormalizePath(path);
            lock (_sync)
            {
                RunWithReconnectNoLock(() =>
                {
                    FtpResponse r = CommandRaw("RMD " + path);
                    Require(r, 250);
                    InvalidateNoLock(path);
                    return 0;
                });
            }
        }

        public void Rename(string oldPath, string newPath)
        {
            oldPath = NormalizePath(oldPath);
            newPath = NormalizePath(newPath);
            lock (_sync)
            {
                RunWithReconnectNoLock(() =>
                {
                    FtpResponse a = CommandRaw("RNFR " + oldPath);
                    Require(a, 350);
                    FtpResponse b = CommandRaw("RNTO " + newPath);
                    Require(b, 250);
                    InvalidateNoLock(oldPath);
                    InvalidateNoLock(newPath);
                    return 0;
                });
            }
        }

        private T RunWithReconnectNoLock<T>(Func<T> operation)
        {
            EnsureConnectedNoLock();
            try
            {
                return operation();
            }
            catch (Exception ex) when (IsConnectionFailure(ex))
            {
                DisconnectNoLock();
                EnsureConnectedNoLock();
                return operation();
            }
        }

        private static bool IsConnectionFailure(Exception ex)
        {
            var ftp = ex as FtpException;
            if (ftp != null)
                return ftp.Code == 421 || ftp.Code == 425 || ftp.Code == 426;
            if (ex is SocketException || ex is TimeoutException || ex is EndOfStreamException)
                return true;
            if (ex is IOException && ex.InnerException != null)
                return IsConnectionFailure(ex.InnerException);
            return false;
        }

        private void EnsureConnectedNoLock()
        {
            if (_control != null && _control.Connected && _reader != null && _writer != null)
                return;

            ValidateCommandPart(_username);
            ValidateCommandPart(_password);

            _control = ConnectTcp(_host, _port, ConnectTimeoutMs);
            _control.ReceiveTimeout = ControlTimeoutMs;
            _control.SendTimeout = ControlTimeoutMs;
            _controlStream = _control.GetStream();
            _reader = new StreamReader(_controlStream, _commandEncoding, false, 1024, true);
            _writer = new StreamWriter(_controlStream, _commandEncoding, 1024, true) { NewLine = "\r\n", AutoFlush = true };

            FtpResponse welcome = ReadResponse();
            Require(welcome, 220);

            FtpResponse user = CommandRaw("USER " + _username);
            if (user.Code == 331 || user.Code == 332)
            {
                FtpResponse pass = CommandRaw("PASS " + _password);
                Require(pass, 230, 202);
            }
            else
                Require(user, 230);

            Require(CommandRaw("TYPE I"), 200);

            // FEAT/OPTS 실패 여부와 무관하게 이 앱은 경로/목록을 UTF-8로 처리한다.
            try { CommandRaw("FEAT"); } catch { }
            try { CommandRaw("OPTS UTF8 ON"); } catch { }
        }

        private void DisconnectNoLock()
        {
            try { _writer?.Dispose(); } catch { }
            try { _reader?.Dispose(); } catch { }
            try { _controlStream?.Dispose(); } catch { }
            try { _control?.Close(); } catch { }
            _writer = null;
            _reader = null;
            _controlStream = null;
            _control = null;
        }

        private List<RemoteEntry> ListNoLock(string path)
        {
            try
            {
                byte[] payload = ReceiveDataCommandNoLock("MLSD " + path, true);
                string text;
                try { text = _strictUtf8.GetString(payload); }
                catch (DecoderFallbackException)
                {
                    throw new FtpException(0, "FTP 서버의 디렉터리 목록이 UTF-8이 아닙니다. 파일명 깨짐 방지를 위해 표시를 중단했습니다.");
                }
                return ParseMlsd(text, path);
            }
            catch (FtpException ex) when (ex.Code == 500 || ex.Code == 501 || ex.Code == 502 || ex.Code == 504)
            {
                byte[] payload = ReceiveDataCommandNoLock("LIST " + path, true);
                string text;
                try { text = _strictUtf8.GetString(payload); }
                catch (DecoderFallbackException)
                {
                    throw new FtpException(0, "FTP 서버의 LIST 결과가 UTF-8이 아닙니다. 파일명 깨짐 방지를 위해 표시를 중단했습니다.");
                }
                return ParseListFallback(text, path);
            }
        }

        private byte[] ReceiveDataCommandNoLock(string command, bool listing)
        {
            using (TcpClient data = OpenPassiveNoLock())
            {
                FtpResponse start = CommandRaw(command);
                if (start.Code != 125 && start.Code != 150)
                    throw new FtpException(start.Code, start.Text);

                using (var ms = new MemoryStream())
                using (NetworkStream ns = data.GetStream())
                {
                    if (!listing)
                    {
                        ns.ReadTimeout = 10000;
                        ns.CopyTo(ms);
                    }
                    else
                    {
                        Stopwatch sw = Stopwatch.StartNew();
                        ns.ReadTimeout = DirectoryFirstDataTimeoutMs;
                        byte[] buffer = new byte[16 * 1024];
                        bool firstRead = true;
                        while (true)
                        {
                            int read = ns.Read(buffer, 0, buffer.Length);
                            if (read <= 0) break;
                            ms.Write(buffer, 0, read);

                            int remain = DirectoryTotalTimeoutMs - (int)sw.ElapsedMilliseconds;
                            if (remain <= 0)
                                throw new TimeoutException("FTP directory listing timed out.");
                            if (firstRead) firstRead = false;
                            ns.ReadTimeout = Math.Max(250, remain);
                        }
                    }

                    byte[] result = ms.ToArray();
                    FtpResponse done = ReadResponse();
                    Require(done, 226, 250);
                    return result;
                }
            }
        }

        private void TransferDownloadNoLock(string remotePath, string localPath)
        {
            using (TcpClient data = OpenPassiveNoLock())
            {
                FtpResponse start = CommandRaw("RETR " + remotePath);
                if (start.Code != 125 && start.Code != 150)
                    throw new FtpException(start.Code, start.Text);

                using (NetworkStream ns = data.GetStream())
                using (FileStream fs = new FileStream(localPath, FileMode.Create, FileAccess.Write, FileShare.Read))
                {
                    ns.ReadTimeout = StreamingReadTimeoutMs;
                    ns.CopyTo(fs);
                }

                Require(ReadResponse(), 226, 250);
            }
        }

        private byte[] TransferReadRangeReusableNoLock(string remotePath, long offset, int maxLength)
        {
            TcpClient data = null;
            try
            {
                data = OpenPassiveNoLock();

                if (offset > 0)
                {
                    FtpResponse rest = CommandRaw("REST " + offset.ToString(CultureInfo.InvariantCulture));
                    if (rest.Code != 350)
                        throw new FtpException(rest.Code,
                            "FTP 서버가 REST 부분 읽기를 지원하지 않습니다. 대용량 파일 시킹에 REST 지원이 필요합니다. " + rest.Text);
                }

                FtpResponse start = CommandRaw("RETR " + remotePath);
                if (start.Code != 125 && start.Code != 150)
                    throw new FtpException(start.Code, start.Text);

                // 요청 길이보다 1바이트 더 읽어서 실제 EOF인지 부분 전송인지 구분한다.
                byte[] buffer = new byte[maxLength + 1];
                int total = 0;
                bool eof = false;
                using (NetworkStream ns = data.GetStream())
                {
                    ns.ReadTimeout = MetadataReadTimeoutMs;
                    while (total < buffer.Length)
                    {
                        int n = ns.Read(buffer, total, buffer.Length - total);
                        if (n <= 0)
                        {
                            eof = true;
                            break;
                        }
                        total += n;
                    }
                }
                data = null;

                if (eof)
                {
                    Require(ReadResponse(), 226, 250);
                }
                else
                {
                    // Explorer MP3 metadata probes stop RETR before EOF. Do not wait up to 3 seconds
                    // trying to resynchronize that control channel; discard it immediately.
                    DisconnectNoLock();
                }

                int resultLength = Math.Min(total, maxLength);
                if (resultLength == buffer.Length) return buffer;
                byte[] trimmed = new byte[resultLength];
                if (resultLength > 0) System.Buffer.BlockCopy(buffer, 0, trimmed, 0, resultLength);
                return trimmed;
            }
            finally
            {
                try { data?.Close(); } catch { }
            }
        }

        private bool ResyncAfterPartialReadNoLock()
        {
            if (_control == null || _writer == null || _reader == null) return false;

            int oldTimeout = _control.ReceiveTimeout;
            try
            {
                _control.ReceiveTimeout = 3000;

                // 데이터 소켓을 닫은 뒤 NOOP을 marker로 보내고, 전송 종료 응답(426/226 등)을
                // 모두 소모해서 NOOP의 200 응답까지 도달한 경우에만 이 제어 세션을 재사용한다.
                _writer.WriteLine("NOOP");
                for (int i = 0; i < 8; i++)
                {
                    FtpResponse r = ReadResponse();
                    if (r.Code == 200) return true;
                    if (r.Code == 225 || r.Code == 226 || r.Code == 250 ||
                        r.Code == 425 || r.Code == 426 || r.Code == 450 || r.Code == 451)
                        continue;
                    return false;
                }
                return false;
            }
            catch
            {
                return false;
            }
            finally
            {
                if (_control != null) _control.ReceiveTimeout = oldTimeout;
            }
        }

        private void TransferUploadNoLock(string localPath, string remotePath)
        {
            using (TcpClient data = OpenPassiveNoLock())
            {
                FtpResponse start = CommandRaw("STOR " + remotePath);
                if (start.Code != 125 && start.Code != 150)
                    throw new FtpException(start.Code, start.Text);

                using (FileStream fs = new FileStream(localPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                using (NetworkStream ns = data.GetStream())
                {
                    ns.WriteTimeout = StreamingWriteTimeoutMs;
                    fs.CopyTo(ns);
                    ns.Flush();
                }

                Require(ReadResponse(), 226, 250);
            }
        }

        private TcpClient OpenPassiveNoLock()
        {
            FtpResponse epsv = CommandRaw("EPSV");
            if (epsv.Code == 229)
            {
                Match m = Regex.Match(epsv.Text, @"\(\|\|\|(\d+)\|\)");
                if (!m.Success) throw new FtpException(epsv.Code, "EPSV 응답을 해석할 수 없습니다: " + epsv.Text);
                return ConnectTcp(_host, int.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture), ConnectTimeoutMs);
            }

            FtpResponse pasv = CommandRaw("PASV");
            Require(pasv, 227);
            Match p = Regex.Match(pasv.Text, @"(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)");
            if (!p.Success) throw new FtpException(pasv.Code, "PASV 응답을 해석할 수 없습니다: " + pasv.Text);

            string ip = string.Join(".", p.Groups[1].Value, p.Groups[2].Value, p.Groups[3].Value, p.Groups[4].Value);
            int port = int.Parse(p.Groups[5].Value, CultureInfo.InvariantCulture) * 256 + int.Parse(p.Groups[6].Value, CultureInfo.InvariantCulture);
            if (ip == "0.0.0.0" || ip == "127.0.0.1") ip = _host;
            return ConnectTcp(ip, port, ConnectTimeoutMs);
        }

        private FtpResponse CommandRaw(string command)
        {
            ValidateCommandPart(command);
            _writer.WriteLine(command);
            return ReadResponse();
        }

        private FtpResponse ReadResponse()
        {
            string first = _reader.ReadLine();
            if (first == null) throw new EndOfStreamException("FTP 연결이 종료되었습니다.");
            if (first.Length < 3 || !char.IsDigit(first[0]) || !char.IsDigit(first[1]) || !char.IsDigit(first[2]))
                throw new IOException("잘못된 FTP 응답: " + first);

            int code = int.Parse(first.Substring(0, 3), CultureInfo.InvariantCulture);
            var sb = new StringBuilder(first);
            if (first.Length >= 4 && first[3] == '-')
            {
                string terminator = code.ToString("000", CultureInfo.InvariantCulture) + " ";
                while (true)
                {
                    string line = _reader.ReadLine();
                    if (line == null) throw new EndOfStreamException("FTP 다중행 응답 중 연결이 종료되었습니다.");
                    sb.Append('\n').Append(line);
                    if (line.StartsWith(terminator, StringComparison.Ordinal)) break;
                }
            }
            return new FtpResponse { Code = code, Text = sb.ToString() };
        }

        private static void Require(FtpResponse r, params int[] allowed)
        {
            foreach (int a in allowed) if (r.Code == a) return;
            throw new FtpException(r.Code, r.Text);
        }

        private static TcpClient ConnectTcp(string host, int port, int timeoutMs)
        {
            var c = new TcpClient();
            IAsyncResult ar = c.BeginConnect(host, port, null, null);
            try
            {
                if (!ar.AsyncWaitHandle.WaitOne(timeoutMs))
                {
                    c.Close();
                    throw new TimeoutException("FTP 연결 시간이 초과되었습니다.");
                }
                c.EndConnect(ar);
                return c;
            }
            catch
            {
                try { c.Close(); } catch { }
                throw;
            }
            finally
            {
                ar.AsyncWaitHandle.Close();
            }
        }

        private static void ParseServer(string input, out string host, out int port)
        {
            string s = (input ?? "").Trim();
            if (s.StartsWith("ftp://", StringComparison.OrdinalIgnoreCase)) s = s.Substring(6);
            int slash = s.IndexOf('/');
            if (slash >= 0) s = s.Substring(0, slash);
            port = 21;

            int colon = s.LastIndexOf(':');
            if (colon > 0 && colon < s.Length - 1)
            {
                int p;
                if (int.TryParse(s.Substring(colon + 1), NumberStyles.None, CultureInfo.InvariantCulture, out p) && p > 0 && p <= 65535)
                {
                    port = p;
                    s = s.Substring(0, colon);
                }
            }
            if (string.IsNullOrWhiteSpace(s)) throw new ArgumentException("FTP 주소가 비어 있습니다.");
            host = s;
        }

        private static void ValidateCommandPart(string value)
        {
            if (value != null && (value.IndexOf('\r') >= 0 || value.IndexOf('\n') >= 0))
                throw new ArgumentException("FTP 명령에는 줄바꿈 문자를 사용할 수 없습니다.");
        }

        public static string NormalizePath(string path)
        {
            if (string.IsNullOrEmpty(path) || path == "\\" || path == "/") return "/";
            string p = path.Replace('\\', '/');
            if (!p.StartsWith("/", StringComparison.Ordinal)) p = "/" + p;
            while (p.Contains("//")) p = p.Replace("//", "/");
            if (p.Length > 1 && p.EndsWith("/", StringComparison.Ordinal)) p = p.TrimEnd('/');
            ValidateCommandPart(p);
            return p;
        }

        public static string ParentPath(string path)
        {
            path = NormalizePath(path);
            if (path == "/") return "/";
            int i = path.LastIndexOf('/');
            return i <= 0 ? "/" : path.Substring(0, i);
        }

        public static string LeafName(string path)
        {
            path = NormalizePath(path);
            if (path == "/") return "";
            int i = path.LastIndexOf('/');
            return path.Substring(i + 1);
        }

        private static string CombinePath(string parent, string leaf)
        {
            parent = NormalizePath(parent);
            return parent == "/" ? "/" + leaf : parent + "/" + leaf;
        }

        private void InvalidateNoLock(string path)
        {
            path = NormalizePath(path);
            RemoveDirectoryCacheItemNoLock(path);
            RemoveDirectoryCacheItemNoLock(ParentPath(path));
        }

        private static RemoteEntry CloneEntry(RemoteEntry e)
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
        {
            var dst = new List<RemoteEntry>(src.Count);
            foreach (RemoteEntry e in src)
                dst.Add(new RemoteEntry { Name = e.Name, FullPath = e.FullPath, IsDirectory = e.IsDirectory, Size = e.Size, ModifiedUtc = e.ModifiedUtc });
            return dst;
        }

        private static List<RemoteEntry> ParseMlsd(string text, string parent)
        {
            var result = new List<RemoteEntry>();
            string[] lines = text.Replace("\r\n", "\n").Split(new[] { '\n' }, StringSplitOptions.RemoveEmptyEntries);
            foreach (string raw in lines)
            {
                int sp = raw.IndexOf(' ');
                if (sp <= 0 || sp >= raw.Length - 1) continue;
                string facts = raw.Substring(0, sp);
                string name = raw.Substring(sp + 1);
                if (name == "." || name == ".." || name.Length == 0) continue;

                bool isDir = false;
                long size = 0;
                DateTime modified = DateTime.UtcNow;
                string[] parts = facts.Split(new[] { ';' }, StringSplitOptions.RemoveEmptyEntries);
                foreach (string part in parts)
                {
                    int eq = part.IndexOf('=');
                    if (eq <= 0) continue;
                    string k = part.Substring(0, eq).ToLowerInvariant();
                    string v = part.Substring(eq + 1);
                    if (k == "type")
                    {
                        if (v == "cdir" || v == "pdir") { isDir = true; name = ""; break; }
                        isDir = v.StartsWith("dir", StringComparison.OrdinalIgnoreCase);
                    }
                    else if (k == "size") long.TryParse(v, NumberStyles.Integer, CultureInfo.InvariantCulture, out size);
                    else if (k == "modify")
                    {
                        DateTime dt;
                        if (DateTime.TryParseExact(v.Substring(0, Math.Min(14, v.Length)), "yyyyMMddHHmmss", CultureInfo.InvariantCulture,
                            DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out dt)) modified = dt;
                    }
                }
                if (name.Length == 0) continue;
                result.Add(new RemoteEntry
                {
                    Name = name,
                    FullPath = CombineRemote(parent, name),
                    IsDirectory = isDir,
                    Size = isDir ? 0 : size,
                    ModifiedUtc = modified
                });
            }
            result.Sort((a, b) => StringComparer.OrdinalIgnoreCase.Compare(a.Name, b.Name));
            return result;
        }

        private static List<RemoteEntry> ParseListFallback(string text, string parent)
        {
            var result = new List<RemoteEntry>();
            string[] lines = text.Replace("\r\n", "\n").Split(new[] { '\n' }, StringSplitOptions.RemoveEmptyEntries);
            Regex unix = new Regex(@"^(?<mode>[d-])\S*\s+\d+\s+\S+\s+\S+\s+(?<size>\d+)\s+\S+\s+\d{1,2}\s+(?:\d{2}:\d{2}|\d{4})\s+(?<name>.+)$");
            Regex win = new Regex(@"^\d{2}-\d{2}-\d{2,4}\s+\d{1,2}:\d{2}(?:AM|PM)?\s+(?<kind><DIR>|\d+)\s+(?<name>.+)$", RegexOptions.IgnoreCase);
            foreach (string line0 in lines)
            {
                string line = line0.TrimEnd('\r');
                string name = null;
                bool isDir = false;
                long size = 0;
                Match m = unix.Match(line);
                if (m.Success)
                {
                    isDir = m.Groups["mode"].Value == "d";
                    long.TryParse(m.Groups["size"].Value, out size);
                    name = m.Groups["name"].Value;
                }
                else
                {
                    m = win.Match(line);
                    if (m.Success)
                    {
                        string kind = m.Groups["kind"].Value;
                        isDir = kind.Equals("<DIR>", StringComparison.OrdinalIgnoreCase);
                        if (!isDir) long.TryParse(kind, out size);
                        name = m.Groups["name"].Value;
                    }
                }
                if (string.IsNullOrEmpty(name) || name == "." || name == "..") continue;
                result.Add(new RemoteEntry { Name = name, FullPath = CombineRemote(parent, name), IsDirectory = isDir, Size = isDir ? 0 : size, ModifiedUtc = DateTime.UtcNow });
            }
            result.Sort((a, b) => StringComparer.OrdinalIgnoreCase.Compare(a.Name, b.Name));
            return result;
        }

        private static string CombineRemote(string parent, string name)
        {
            parent = NormalizePath(parent);
            return parent == "/" ? "/" + name : parent + "/" + name;
        }
    }
}
