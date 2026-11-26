import { useCallback, useEffect, useState } from 'react';
import {
  type CameraClient,
  type DirEntry,
  type PhotoEntry,
  formatBytes,
} from '../api/cameraClient';
import { PhotoPreviewModal } from './PhotoPreviewModal';

interface PhotoBrowserProps {
  client: CameraClient;
  connected: boolean;
  rawRecMode?: string;
  onError: (msg: string) => void;
}

export function PhotoBrowser({ client, connected, rawRecMode, onError }: PhotoBrowserProps) {
  const [currentDir, setCurrentDir] = useState<string>('');
  const [dirs, setDirs] = useState<DirEntry[]>([]);
  const [photos, setPhotos] = useState<PhotoEntry[]>([]);
  const [totalInDir, setTotalInDir] = useState<number>(0);
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState<{ url: string; downloadUrl: string; name: string } | null>(null);

  const loadDir = useCallback(
    async (dirPath: string) => {
      if (!connected) return;
      setLoading(true);
      try {
        const res = await client.listPhotos(dirPath, 100);
        setCurrentDir(res.dir ?? dirPath);
        setDirs(res.dirs ?? []);
        setPhotos(res.photos ?? []);
        setTotalInDir(res.totalPhotosInDir ?? res.photos?.length ?? 0);
      } catch (err) {
        onError(err instanceof Error ? err.message : String(err));
      } finally {
        setLoading(false);
      }
    },
    [client, connected, onError],
  );

  useEffect(() => {
    if (connected) {
      void loadDir(currentDir);
    }
  }, [connected]); // eslint-disable-line react-hooks/exhaustive-deps

  const goUp = () => {
    if (!currentDir) return;
    const parts = currentDir.split('/').filter(Boolean);
    parts.pop();
    void loadDir(parts.join('/'));
  };

  const breadcrumbs = currentDir.split('/').filter(Boolean);

  return (
    <section className="panel">
      <div className="panel-header">
        <div className="panel-title">
          <span>相机相册</span>
          <span className="badge">{totalInDir} 个文件</span>
          {connected && (rawRecMode === 'compress' || rawRecMode === 'uncompress') && (
            <span className="badge">遥控模式 RAW：{rawRecMode === 'compress' ? '压缩' : '未压缩'}</span>
          )}
        </div>
        <div className="toolbar-row">
          <button
            type="button"
            className="btn btn-sm"
            disabled={!connected}
            onClick={() => {
              const now = Date.now();
              setPreview({
                url: client.latestPhotoPreviewUrl(now),
                downloadUrl: client.latestPhotoUrl(now),
                name: '最新照片',
              });
            }}
            title="查看最新拍摄的照片"
          >
            最新照片
          </button>
          <button
            type="button"
            className="btn btn-sm"
            disabled={!connected || loading}
            onClick={() => void loadDir(currentDir)}
            title="刷新当前文件夹"
          >
            {loading ? '加载中…' : '刷新'}
          </button>
        </div>
      </div>

      <div className="breadcrumb-bar">
        <button
          type="button"
          className="btn btn-sm"
          disabled={!connected || !currentDir}
          onClick={goUp}
          title="返回上一级"
          aria-label="返回上一级"
        >
          上一级
        </button>
        <button
          type="button"
          className="crumb-btn"
          disabled={!connected}
          onClick={() => void loadDir('')}
        >
          DCIM
        </button>
        {breadcrumbs.map((part, idx) => {
          const path = breadcrumbs.slice(0, idx + 1).join('/');
          return (
            <span key={path} className="crumb-sep">
              /
              <button
                type="button"
                className="crumb-btn"
                disabled={!connected}
                onClick={() => void loadDir(path)}
              >
                {part}
              </button>
            </span>
          );
        })}
      </div>

      <div className="photo-list">
        {dirs.map((d) => (
          <button
            key={d.path}
            type="button"
            className="list-row list-row-dir"
            onClick={() => void loadDir(d.path)}
          >
            <span className="row-name">文件夹 {d.name}</span>
            <span className="row-meta">打开 &rarr;</span>
          </button>
        ))}

        {photos.map((p) => {
          const dlUrl = client.photoDownloadUrl(p.path);
          return (
            <div key={p.path} className="list-row">
              <span className="row-name">{p.name}</span>
              <span className="row-meta">{formatBytes(p.size)}</span>
              <div className="row-actions">
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => {
                    setPreview({ url: client.photoPreviewUrl(p.path), downloadUrl: dlUrl, name: p.name });
                  }}
                >
                  查看
                </button>
                <a className="btn btn-sm" href={dlUrl} download={p.name}>
                  下载
                </a>
              </div>
            </div>
          );
        })}

        {connected && !loading && dirs.length === 0 && photos.length === 0 && (
          <div className="empty-state">文件夹为空</div>
        )}
        {!connected && <div className="empty-state">连接相机后可浏览存储卡</div>}
      </div>

      <PhotoPreviewModal
        url={preview?.url ?? null}
        title={preview?.name ?? ''}
        downloadUrl={preview?.downloadUrl}
        onClose={() => setPreview(null)}
      />
    </section>
  );
}
