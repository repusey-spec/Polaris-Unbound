using System;
using System.Diagnostics;
using System.IO;
using System.Threading;

namespace TinyFtpDrive
{
    internal static class TraceLog
    {
        private static readonly object Sync = new object();
        private static readonly Stopwatch Clock = Stopwatch.StartNew();
        private static long Sequence;
        private static bool Started;
        private const long MaxBytes = 16L * 1024 * 1024;

        public static string FilePath
        {
            get { return Path.Combine(AppConfig.ConfigDirectory, "trace.log"); }
        }

        public static void StartNewSession()
        {
            lock (Sync)
            {
                try
                {
                    Directory.CreateDirectory(AppConfig.ConfigDirectory);
                    File.WriteAllText(FilePath,
                        "=== yongbongE v0.15 TRACE " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff") + " ===" +
                        Environment.NewLine);
                    Sequence = 0;
                    Clock.Restart();
                    Started = true;
                }
                catch { }
            }
        }

        public static void Write(string category, string message)
        {
            try
            {
                lock (Sync)
                {
                    if (!Started)
                    {
                        Directory.CreateDirectory(AppConfig.ConfigDirectory);
                        Started = true;
                    }

                    if (File.Exists(FilePath) && new FileInfo(FilePath).Length >= MaxBytes)
                    {
                        File.WriteAllText(FilePath,
                            "=== TRACE ROTATED " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff") + " ===" +
                            Environment.NewLine);
                        Sequence = 0;
                        Clock.Restart();
                    }

                    long seq = Interlocked.Increment(ref Sequence);
                    string safe = (message ?? "").Replace("\r", "\\r").Replace("\n", "\\n");
                    string line = string.Format(
                        "{0:000000} {1,9:0.000}ms T{2:000} [{3}] {4}",
                        seq,
                        Clock.Elapsed.TotalMilliseconds,
                        Thread.CurrentThread.ManagedThreadId,
                        category ?? "?",
                        safe);
                    File.AppendAllText(FilePath, line + Environment.NewLine);
                }
            }
            catch { }
        }
    }
}
