import { useEffect, useRef, useState } from 'react';
import type { CameraClient, RawMode, RawSettings } from '../api/cameraClient';

const OPTIONS = [
  { value: 'compress', label: '压缩' },
  { value: 'uncompress', label: '未压缩' },
] as const;

interface RawTypeControlProps {
  client: CameraClient;
  connected: boolean;
  capturing: boolean;
  reportedMode?: string;
}

export function RawTypeControl({ client, connected, capturing, reportedMode }: RawTypeControlProps) {
  const [actual, setActual] = useState<RawSettings | null>(null);
  const [pending, setPending] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [readFailed, setReadFailed] = useState(false);
  const writeRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!connected) {
      setActual(null);
      setPending(false);
      setNotice(null);
      setReadFailed(false);
    }
    return () => {
      writeRef.current?.abort();
      writeRef.current = null;
    };
  }, [client, connected]);

  useEffect(() => {
    if (!connected || capturing || pending) return;
    const read = new AbortController();
    void client.getRawSettings(read.signal).then((settings) => {
      if (read.signal.aborted || writeRef.current) return;
      setActual(settings);
      setReadFailed(false);
    }).catch(() => {
      if (read.signal.aborted || writeRef.current) return;
      setActual(null);
      setReadFailed(true);
    });
    return () => read.abort();
  }, [client, connected, capturing, pending, reportedMode]);

  const change = async (mode: RawMode) => {
    if (!connected || capturing || writeRef.current || !actual?.options.includes(mode) || actual.mode === mode) return;
    const write = new AbortController();
    writeRef.current = write;
    setPending(true);
    setNotice(null);
    try {
      const confirmed = await client.setRawMode(mode, write.signal);
      if (write.signal.aborted) return;
      setActual(confirmed);
      setReadFailed(false);
      if (confirmed.mode !== mode) setNotice('相机未应用所选 RAW 类型');
    } catch (error) {
      if (write.signal.aborted) return;
      setActual(null);
      setNotice(error instanceof Error ? error.message : 'RAW 类型切换失败');
    } finally {
      if (writeRef.current === write) {
        writeRef.current = null;
        setPending(false);
      }
    }
  };

  const selected = OPTIONS.find(({ value }) => value === actual?.mode);
  const currentLabel = !connected ? '相机未连接'
    : selected?.label ?? actual?.mode ?? (readFailed || actual ? '无法读取' : '正在读取…');
  const status = pending ? '正在等待相机确认…' : notice
    ?? (connected && actual && !actual.options.includes('uncompress') ? '相机当前模式未提供未压缩选项' : null);

  return (
    <div className="metric-card metric-card-wide">
      <div className="metric-header-row">
        <span className="metric-label">RAW 类型</span>
        <span className="badge" aria-live="polite">当前生效：{currentLabel}</span>
      </div>
      <div className="stepper-row" role="group" aria-label="RAW 类型">
        {OPTIONS.map(({ value, label }) => (
          <button
            key={value}
            type="button"
            className={`btn btn-sm ${actual?.mode === value ? 'btn-active' : ''}`}
            aria-pressed={actual?.mode === value}
            disabled={!connected || capturing || pending || !actual?.options.includes(value)}
            onClick={() => void change(value)}
          >
            {label}
          </button>
        ))}
      </div>
      {status && <span className="muted-tag" role="status">{status}</span>}
    </div>
  );
}
