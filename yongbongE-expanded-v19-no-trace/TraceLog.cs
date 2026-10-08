using System;

namespace TinyFtpDrive
{
    internal static class TraceLog
    {
        // v0.19: preserve every v0.16 call site, but make tracing a complete no-op.
        // This deliberately changes no FTP, WinFsp, cache, timeout, pool, or I/O behavior.
        public static string FilePath { get { return string.Empty; } }
        public static void StartNewSession() { }
        public static void Write(string category, string message) { }
    }
}
