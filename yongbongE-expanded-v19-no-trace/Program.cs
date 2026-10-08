using System;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;
using Fsp;

namespace TinyFtpDrive
{
    internal static class Program
    {
        private const string MutexName = "Local\\yongbongE.SingleInstance";

        [STAThread]
        private static void Main()
        {
            bool created;
            using (var mutex = new Mutex(true, MutexName, out created))
            {
                if (!created)
                {
                    MessageBox.Show("yongbongE가 이미 실행 중입니다.\n설정은 작업표시줄 알림 영역 아이콘에서 열 수 있습니다.",
                        "yongbongE", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);

                AppConfig cfg = AppConfig.Load();
                if (!cfg.IsComplete)
                {
                    using (var form = new SettingsForm(cfg))
                    {
                        if (form.ShowDialog() != DialogResult.OK) return;
                        cfg = form.ResultConfig;
                        cfg.Save(Application.ExecutablePath);
                    }
                }
                else
                {
                    cfg.Save(Application.ExecutablePath);
                }

                try
                {
                    var context = new TrayAppContext(cfg);
                    try { Application.Run(context); }
                    finally { context.Shutdown(); }
                }
                catch (Exception ex)
                {
                    MessageBox.Show(
                        "yongbongE를 시작하지 못했습니다.\n\n" + ex.Message +
                        "\n\nWinFsp 2.x가 설치되어 있는지도 확인해 주세요.",
                        "yongbongE", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            }
        }
    }

    internal sealed class TrayAppContext : ApplicationContext
    {
        private AppConfig _config;
        private NotifyIcon _tray;
        private FileSystemHost _host;
        private FtpFileSystem _fileSystem;
        private bool _shutdown;

        public TrayAppContext(AppConfig config)
        {
            _config = config;
            try
            {
                MountDrive();
                BuildTray();
            }
            catch
            {
                Shutdown();
                throw;
            }
        }

        private void BuildTray()
        {
            var menu = new ContextMenuStrip();
            menu.Items.Add("설정", null, (s, e) => ShowSettings());
            menu.Items.Add("FTP 연결 초기화", null, (s, e) => ResetConnection());
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("종료", null, (s, e) => ExitApplication());

            _tray = new NotifyIcon
            {
                Icon = SystemIcons.Application,
                Text = "yongbongE  " + _config.DriveLetter,
                Visible = true,
                ContextMenuStrip = menu
            };
            _tray.DoubleClick += (s, e) => ShowSettings();
        }

        private void MountDrive()
        {
            UnmountDrive();
            _fileSystem = new FtpFileSystem(_config);
            _host = new FileSystemHost(_fileSystem);

            string drive = AppConfig.NormalizeDriveLetter(_config.DriveLetter);
            // Fine-grained WinFsp dispatcher: slow MP3 property reads must never serialize ReadDirectory.
            int status = _host.MountEx(drive, 0, null, false, 0);
            if (status < 0)
            {
                _host.Dispose();
                _host = null;
                _fileSystem.Dispose();
                _fileSystem = null;
                throw new InvalidOperationException(
                    drive + " 드라이브를 마운트할 수 없습니다. 해당 문자가 이미 사용 중인지 확인해 주세요.\nNTSTATUS=0x" + status.ToString("X8"));
            }
        }

        private void UnmountDrive()
        {
            if (_host != null)
            {
                try { _host.Unmount(); } catch { }
                try { _host.Dispose(); } catch { }
                _host = null;
            }
            if (_fileSystem != null)
            {
                try { _fileSystem.Dispose(); } catch { }
                _fileSystem = null;
            }
        }

        private void ShowSettings()
        {
            using (var form = new SettingsForm(_config))
            {
                if (form.ShowDialog() != DialogResult.OK) return;
                AppConfig next = form.ResultConfig;
                next.Save(Application.ExecutablePath);
                _config = next;
                try
                {
                    MountDrive();
                    if (_tray != null) _tray.Text = "yongbongE  " + _config.DriveLetter;
                    _tray?.ShowBalloonTip(1500, "yongbongE",
                        "설정을 저장했습니다. " + _config.DriveLetter + "는 다음 접근 때 FTP에 연결합니다.", ToolTipIcon.Info);
                }
                catch (Exception ex)
                {
                    MessageBox.Show(ex.Message, "yongbongE", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            }
        }

        private void ResetConnection()
        {
            _fileSystem?.ResetConnection();
            _tray?.ShowBalloonTip(1200, "yongbongE",
                "FTP 세션을 비웠습니다. 다음 " + _config.DriveLetter + " 접근 때 다시 연결합니다.", ToolTipIcon.Info);
        }

        private void ExitApplication()
        {
            Shutdown();
            ExitThread();
        }

        protected override void ExitThreadCore()
        {
            Shutdown();
            base.ExitThreadCore();
        }

        public void Shutdown()
        {
            if (_shutdown) return;
            _shutdown = true;
            UnmountDrive();
            if (_tray != null)
            {
                _tray.Visible = false;
                _tray.Dispose();
                _tray = null;
            }
        }
    }
}
