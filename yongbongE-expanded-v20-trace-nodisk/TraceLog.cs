using System;
using System.Diagnostics;
using System.Threading;

namespace TinyFtpDrive
{
    internal static class TraceLog
    {
        private static readonly object Sync = new object();
        private static readonly Stopwatch Clock = Stopwatch.StartNew();
        private static long Sequence;
        private static bool Started;

        // Keep the v0.16 tracing call structure and global serialization,
        // but perform no filesystem I/O at all.
        public static string FilePath
        {
            get { return string.Empty; }
        }

        public static void StartNewSession()
        {
            lock (Sync)
            {
                Sequence = 0;
                Clock.Restart();
                Started = true;
            }
        }

        public static void Write(string category, string message)
        {
            lock (Sync)
            {
                if (!Started)
                    Started = true;

                // Preserve the v0.16 ordering/serialization effect without creating trace.log.
                Interlocked.Increment(ref Sequence);
                _ = Clock.Elapsed.TotalMilliseconds;
                _ = category;
                _ = message;
            }
        }
    }
}
