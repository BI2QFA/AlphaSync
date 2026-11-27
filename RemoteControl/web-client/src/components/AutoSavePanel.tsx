import { isDirectoryPickerSupported } from '../api/localSave';

interface AutoSavePanelProps {
  enabled: boolean;
  onToggle: (enabled: boolean) => void;
  dirName: string | null;
  onPickDirectory: () => void;
  savedCount: number;
  lastSavedFile: string | null;
  savingStatus: string | null;
  connected: boolean;
  getLatestPhotoUrl: () => string;
  onViewLatestPhoto: () => void;
  onOpenSavedPhoto?: () => void;
}

export function AutoSavePanel({
  enabled,
  onToggle,
  dirName,
  onPickDirectory,
  savedCount,
  lastSavedFile,
  savingStatus,
  connected,
  getLatestPhotoUrl,
  onViewLatestPhoto,
  onOpenSavedPhoto,
}: AutoSavePanelProps) {
  const pickerSupported = isDirectoryPickerSupported();

  return (
    <section className="panel">
      <div className="panel-header">
        <div className="panel-title">
          <span>保存</span>
          <button
            type="button"
            className={`btn btn-sm ${enabled ? 'btn-active' : ''}`}
            onClick={() => onToggle(!enabled)}
            aria-pressed={enabled}
            title="Toggle automatic photo download after capture"
            aria-label="Toggle automatic photo download after capture"
          >
            自动
          </button>
          {savedCount > 0 && <span className="badge">{savedCount} saved</span>}
        </div>
        <div className="toolbar-row">
          <a
            className={`btn btn-sm ${!connected ? 'is-disabled' : ''}`}
            href={connected ? getLatestPhotoUrl() : '#'}
            download
            title="Download the most recently captured photo from the camera"
            aria-label="Download latest photo"
            aria-disabled={!connected}
            style={!connected ? { opacity: 0.5, pointerEvents: 'none' } : undefined}
            onClick={(e) => {
              if (!connected) {
                e.preventDefault();
                return;
              }
              e.currentTarget.href = getLatestPhotoUrl();
            }}
          >
            下载最新照片
          </a>
          <button
            type="button"
            className="btn btn-sm"
            disabled={!connected}
            onClick={onViewLatestPhoto}
          >
            查看最新照片
          </button>
        </div>
      </div>

      <div className="autosave-row">
        <div>
          {pickerSupported ? (
            <button
              type="button"
              className={`btn btn-sm ${enabled && !dirName ? 'btn-primary' : ''}`}
              onClick={onPickDirectory}
              title="Choose local folder for auto-saved photos"
            >
              {dirName ? `Folder: ${dirName}` : enabled ? '选择保存文件夹' : '选择本地文件夹'}
            </button>
          ) : (
            <span className="muted-tag">
              Browser download mode (HTTPS/localhost required for folder picker)
            </span>
          )}
        </div>
        {savingStatus ? (
          <span className="autosave-status" title={savingStatus}>
            {savingStatus}
          </span>
        ) : (
          lastSavedFile && (
            <span className="autosave-status" title={`Saved: ${lastSavedFile}`}>
              {'Saved: '}
              {onOpenSavedPhoto ? (
                <button
                  type="button"
                  className="autosave-link"
                  onClick={onOpenSavedPhoto}
                  title={`Open local file ${lastSavedFile} in a new tab`}
                >
                  {lastSavedFile}
                </button>
              ) : (
                lastSavedFile
              )}
            </span>
          )
        )}
      </div>
    </section>
  );
}
