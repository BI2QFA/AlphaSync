import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';

interface PhotoPreviewModalProps {
  url: string | null;
  title: string;
  downloadUrl?: string;
  onClose: () => void;
}

export function PhotoPreviewModal({
  url,
  title,
  downloadUrl,
  onClose,
}: PhotoPreviewModalProps) {
  const [result, setResult] = useState<{ url: string; loaded: boolean } | null>(null);
  useEffect(() => { setResult(null); }, [url]);

  useEffect(() => {
    if (!url) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [url, onClose]);

  if (!url) return null;
  const loadFailed = result?.url === url && !result.loaded;
  const loaded = result?.url === url && result.loaded;

  return createPortal(
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-card" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <span className="modal-title">{title}</span>
          <div className="toolbar-row">
            {(downloadUrl || !loadFailed) && (
              <a
                className="btn btn-sm"
                href={downloadUrl ?? url}
                download={downloadUrl ? '' : title}
              >
                下载原文件
              </a>
            )}
            <button type="button" className="btn btn-sm" onClick={onClose}>
              关闭
            </button>
          </div>
        </div>
        <div className="modal-preview">
          {loadFailed ? (
            <div className="empty-state" role="status">
              {downloadUrl ? '无法加载预览，可以下载原文件查看。' : '无法加载预览。'}
            </div>
          ) : (
            <>
              {!loaded && <div className="empty-state" role="status">正在加载预览…</div>}
              <img
                key={url}
                src={url}
                alt={title}
                className="modal-image"
                hidden={!loaded}
                onLoad={() => setResult({ url, loaded: true })}
                onError={() => setResult({ url, loaded: false })}
              />
            </>
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
}
