import { useEffect, useRef, useState } from 'react';
import type { CameraClient, CameraState } from '../api/cameraClient';

const TONAL_BLOCK_LEVELS = [3, 4, 5, 6, 8];
const SRGB_LINEAR = Float64Array.from({ length: 256 }, (_, value) => {
  const encoded = value / 255;
  return encoded <= 0.04045 ? encoded / 12.92 : ((encoded + 0.055) / 1.055) ** 2.4;
});
// Invert CIE L* at the bin edges so the frame loop only compares linear luminance.
const luminanceAt = (lightness: number) => lightness <= 8
  ? lightness * (3 / 29) ** 3 : ((lightness + 16) / 116) ** 3;
const CIE_BANDS = new Map(TONAL_BLOCK_LEVELS.map((levels) => [levels, {
  limits: Array.from({ length: levels - 1 }, (_, index) => luminanceAt(100 * (index + 1) / levels)),
  grays: Array.from({ length: levels }, (_, band) => {
    const y = luminanceAt(100 * band / (levels - 1));
    const encoded = y <= 0.0031308 ? y * 12.92 : 1.055 * y ** (1 / 2.4) - 0.055;
    return Math.round(encoded * 255);
  }),
}]));

interface LiveViewPanelProps {
  client: CameraClient;
  connected: boolean;
  onCapture: () => void;
  capturing: boolean;
  stackMode: boolean;
  captureMessage: string;
  previewMagnification?: CameraState['previewMagnification'];
  onError?: (msg: string | null) => void;
}

function getLiveViewClientId(): string {
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

async function decodeLiveViewFrame(blob: Blob): Promise<ImageBitmap | HTMLImageElement> {
  try {
    if (typeof createImageBitmap === 'function') return await createImageBitmap(blob);
  } catch {
    // Some WebView versions can load the same JPEG through an image element.
  }
  const url = URL.createObjectURL(blob);
  try {
    const image = new Image();
    return await new Promise<HTMLImageElement>((resolve, reject) => {
      image.onload = () => resolve(image);
      image.onerror = () => reject(new Error('无效的取景画面'));
      image.src = url;
    });
  } finally {
    URL.revokeObjectURL(url);
  }
}

export function LiveViewPanel({
  client,
  connected,
  onCapture,
  capturing,
  stackMode,
  captureMessage,
  previewMagnification,
  onError,
}: LiveViewPanelProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const histCanvasRef = useRef<HTMLCanvasElement | null>(null);

  const [streaming, setStreaming] = useState(true);
  const [pageVisible, setPageVisible] = useState<boolean>(() =>
    typeof document === 'undefined' ? true : !document.hidden,
  );
  const [showGrid, setShowGrid] = useState(true);
  const [peaking, setPeaking] = useState(false);
  const [showHistogram, setShowHistogram] = useState(false);
  const [showTonalBlocks, setShowTonalBlocks] = useState(false);
  const [tonalBlockLevels, setTonalBlockLevels] = useState(4);
  const [cieLightness, setCieLightness] = useState(false);
  const [toolMode, setToolMode] = useState<'focus' | 'magnify'>('focus');
  const [afStatus, setAfStatus] = useState<'idle' | 'focusing' | 'ok' | 'fail'>('idle');
  const [focusPoint, setFocusPoint] = useState<{ x: number; y: number } | null>(null);
  const [autoResetSec, setAutoResetSec] = useState<number>(() => {
    try {
      const saved = localStorage.getItem('pmca_mag_auto_reset_sec');
      if (saved !== null) {
        const n = Number(saved);
        if (Number.isFinite(n) && n >= 0) return n;
      }
    } catch {}
    return 0;
  });
  const [rotationDeg, setRotationDeg] = useState<0 | 90 | 180 | 270>(() => {
    try {
      const saved = Number(localStorage.getItem('pmca_lv_rotation'));
      if (saved === 0 || saved === 90 || saved === 180 || saved === 270) return saved;
    } catch {}
    return 0;
  });

  const [fps, setFps] = useState<number>(0);
  const [frameError, setFrameError] = useState<string | null>(null);
  const [lvMode, setLvMode] = useState<'mjpeg' | 'jpg'>('mjpeg');

  const magEnabled = Boolean(previewMagnification?.enabled);
  const magRatio = previewMagnification?.ratio ?? 100;
  const magLabel = !magEnabled ? '1x' : magRatio >= 900 ? '11.7x' : '5.9x';

  const focusPointRef = useRef<{ x: number; y: number } | null>(null);
  focusPointRef.current = focusPoint;
  const afStatusRef = useRef<'idle' | 'focusing' | 'ok' | 'fail'>('idle');
  afStatusRef.current = afStatus;
  const liveViewCidRef = useRef<string | null>(null);
  const afTimerRef = useRef<number | null>(null);
  const magResetTimerRef = useRef<number | null>(null);

  const clearMagResetTimer = () => {
    if (magResetTimerRef.current !== null) {
      window.clearTimeout(magResetTimerRef.current);
      magResetTimerRef.current = null;
    }
  };

  const scheduleMagReset = (delaySec: number) => {
    clearMagResetTimer();
    if (delaySec <= 0) return;
    magResetTimerRef.current = window.setTimeout(() => {
      magResetTimerRef.current = null;
      void client.stopPreviewMagnification().catch(() => {});
    }, delaySec * 1000);
  };

  const clearAfTimer = () => {
    if (afTimerRef.current !== null) {
      window.clearTimeout(afTimerRef.current);
      afTimerRef.current = null;
    }
  };

  const scheduleAfDismiss = (delayMs = 2000) => {
    clearAfTimer();
    afTimerRef.current = window.setTimeout(() => {
      setAfStatus('idle');
      setFocusPoint(null);
      afTimerRef.current = null;
    }, delayMs);
  };

  useEffect(() => {
    return () => {
      if (afTimerRef.current !== null) {
        window.clearTimeout(afTimerRef.current);
      }
      if (magResetTimerRef.current !== null) {
        window.clearTimeout(magResetTimerRef.current);
      }
    };
  }, []);

  useEffect(() => {
    if (!magEnabled) {
      clearMagResetTimer();
      return;
    }
    scheduleMagReset(autoResetSec);
  }, [magEnabled, autoResetSec]);
  // Keep overlay flags in refs so toggling them doesn't reconnect the MJPEG stream
  const showGridRef = useRef(showGrid);
  showGridRef.current = showGrid;
  const peakingRef = useRef(peaking);
  peakingRef.current = peaking;
  const showHistogramRef = useRef(showHistogram);
  showHistogramRef.current = showHistogram;
  const tonalBlockLevelsRef = useRef(0);
  tonalBlockLevelsRef.current = showTonalBlocks ? tonalBlockLevels : 0;
  const cieLightnessRef = useRef(cieLightness);
  cieLightnessRef.current = cieLightness;
  const rotationRef = useRef<0 | 90 | 180 | 270>(rotationDeg);
  rotationRef.current = rotationDeg;

  const rotateBy = (delta: 90 | 270) => {
    setRotationDeg((prev) => {
      const next = ((prev + delta) % 360) as 0 | 90 | 180 | 270;
      try {
        localStorage.setItem('pmca_lv_rotation', String(next));
      } catch {}
      return next;
    });
    clearAfTimer();
    setAfStatus('idle');
    setFocusPoint(null);
  };

  useEffect(() => {
    const onVisibilityChange = () => {
      setPageVisible(!document.hidden);
    };
    const onPageHide = () => {
      const cid = liveViewCidRef.current;
      if (cid) client.closeLiveView(cid);
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('pagehide', onPageHide);
    };
  }, [client]);

  useEffect(() => {
    if (!connected || !streaming || !pageVisible) {
      setFps(0);
      return;
    }

    let cancelled = false;
    let retryTimer: number | undefined;
    let frameTimer: number | undefined;
    let activeController: AbortController | null = null;
    let activeReader: ReadableStreamDefaultReader<Uint8Array> | null = null;
    const cid = lvMode === 'mjpeg' ? getLiveViewClientId() : null;
    liveViewCidRef.current = cid;
    let frameCount = 0;
    let lastFpsTime = performance.now();

    let pendingBlob: Blob | null = null;
    let isRendering = false;

    const switchToJpeg = () => {
      if (cancelled) return;
      setFps(0);
      setFrameError('取景串流已中断，正在尝试单帧取景…');
      setLvMode('jpg');
    };

    const watchStream = () => {
      window.clearTimeout(frameTimer);
      frameTimer = window.setTimeout(switchToJpeg, 5000);
    };

    const renderLoop = async () => {
      if (isRendering || cancelled) return;
      isRendering = true;
      try {
        while (!cancelled && pendingBlob) {
          const blob = pendingBlob;
          pendingBlob = null;

          const bitmap = await decodeLiveViewFrame(blob);
          if (cancelled) {
            if ('close' in bitmap) bitmap.close();
            break;
          }

          let drawn = false;
          try {
            const canvas = canvasRef.current;
            if (canvas) {
              const deg = rotationRef.current;
              const srcW = bitmap.width || 640;
              const srcH = bitmap.height || 480;
              const targetW = deg % 180 !== 0 ? srcH : srcW;
              const targetH = deg % 180 !== 0 ? srcW : srcH;
              if (canvas.width !== targetW || canvas.height !== targetH) {
                canvas.width = targetW;
                canvas.height = targetH;
              }
              const levels = tonalBlockLevelsRef.current;
              const usePeaking = peakingRef.current;
              const needPixels = levels > 0 || usePeaking || showHistogramRef.current;
              const ctx = canvas.getContext('2d', { willReadFrequently: needPixels });
              if (ctx) {
                if (deg === 0) {
                  ctx.drawImage(bitmap, 0, 0, srcW, srcH);
                } else {
                  ctx.save();
                  ctx.translate(targetW / 2, targetH / 2);
                  ctx.rotate((deg * Math.PI) / 180);
                  ctx.drawImage(bitmap, -srcW / 2, -srcH / 2, srcW, srcH);
                  ctx.restore();
                }
                drawn = true;

                if (needPixels) {
                  const imgData = ctx.getImageData(0, 0, canvas.width, canvas.height);
                  const data = imgData.data;

                  if (showHistogramRef.current && histCanvasRef.current) {
                    drawHistogram(histCanvasRef.current, data);
                  }

                  if (levels > 0 || usePeaking) {
                    applyPreviewEffects(data, canvas.width, canvas.height, levels, usePeaking, cieLightnessRef.current);
                    ctx.putImageData(imgData, 0, 0);
                  }
                }

                // Draw AF target box on main canvas
                if (afStatusRef.current !== 'idle' && focusPointRef.current) {
                  const label =
                    afStatusRef.current === 'focusing'
                      ? 'AF...'
                      : afStatusRef.current === 'ok'
                        ? 'AF OK'
                        : 'AF Fail';
                  drawFocusBox(
                    ctx,
                    focusPointRef.current.x,
                    focusPointRef.current.y,
                    72,
                    72,
                    label,
                  );
                }

                if (showGridRef.current) {
                  drawGrid(ctx, canvas.width, canvas.height);
                }
              }
            }
          } finally {
            if ('close' in bitmap) bitmap.close();
          }
          if (!drawn) continue;
          if (cid) watchStream();
          setFrameError(null);

          frameCount += 1;
          const now = performance.now();
          if (now - lastFpsTime >= 1000) {
            setFps(Math.round((frameCount * 1000) / (now - lastFpsTime)));
            frameCount = 0;
            lastFpsTime = now;
          }
        }
      } catch (err) {
        if (!cid && !cancelled) throw err;
        // A bad frame is skipped. The stream watchdog handles repeated failures.
      } finally {
        isRendering = false;
      }
    };

    const startStream = async (streamId: string) => {
      if (cancelled) return;
      activeController = new AbortController();
      watchStream();
      try {
        const res = await fetch(client.liveViewMjpegUrl(streamId), {
          signal: activeController.signal,
          cache: 'no-store',
        });

        if (!res.ok || !res.body) {
          throw new Error(`HTTP ${res.status}`);
        }

        const reader = res.body.getReader();
        activeReader = reader;
        let buffer = new Uint8Array(0);
        const decoder = new TextDecoder('ascii');

        while (!cancelled) {
          const { value, done } = await reader.read();
          if (done || cancelled) break;
          if (!value || value.length === 0) continue;

          const merged = new Uint8Array(buffer.length + value.length);
          merged.set(buffer);
          merged.set(value, buffer.length);
          buffer = merged;

          // Parse complete multipart/x-mixed-replace frames by Content-Length
          while (true) {
            const headerEnd = findHeaderEnd(buffer);
            if (headerEnd === -1) break;

            const headerText = decoder.decode(buffer.subarray(0, headerEnd));
            const match = /Content-Length:\s*(\d+)/i.exec(headerText);
            if (!match) {
              buffer = buffer.subarray(headerEnd + 4);
              continue;
            }
            const contentLen = Number(match[1]);
            const bodyStart = headerEnd + 4;
            if (buffer.length < bodyStart + contentLen) {
              break;
            }

            // Copy frame slice & overwrite any un-decoded previous frame (zero backlog)
            const jpegCopy = buffer.slice(bodyStart, bodyStart + contentLen);
            buffer = buffer.subarray(bodyStart + contentLen);
            pendingBlob = new Blob([jpegCopy], { type: 'image/jpeg' });
            void renderLoop();
          }
        }
      } catch {
        // Fall back on network errors as well as a clean end of stream.
      }
      switchToJpeg();
    };

    const loadJpeg = async () => {
      if (cancelled) return;
      const controller = new AbortController();
      activeController = controller;
      const timeout = window.setTimeout(() => controller.abort(), 5000);
      let delay = 100;
      try {
        const res = await fetch(client.liveViewJpgUrl(), { signal: controller.signal, cache: 'no-store' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const blob = await res.blob();
        if (!cancelled) {
          pendingBlob = blob;
          await renderLoop();
        }
      } catch {
        if (!cancelled) {
          setFrameError('相机暂未返回取景画面，正在重试…');
          setFps(0);
          delay = 1500;
        }
      } finally {
        window.clearTimeout(timeout);
      }
      if (!cancelled) retryTimer = window.setTimeout(() => void loadJpeg(), delay);
    };

    if (cid) {
      void startStream(cid);
    } else {
      void loadJpeg();
    }

    return () => {
      cancelled = true;
      void activeReader?.cancel().catch(() => {});
      activeController?.abort();
      if (cid) client.closeLiveView(cid);
      liveViewCidRef.current = null;
      window.clearTimeout(frameTimer);
      window.clearTimeout(retryTimer);
    };
  }, [client, connected, streaming, pageVisible, lvMode]);

  const handleCanvasClick = async (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!connected || (stackMode && toolMode === 'focus')) return;
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const imgRatio = canvas.width / canvas.height;
    const boxRatio = rect.width / rect.height;
    let renderWidth = rect.width;
    let renderHeight = rect.height;
    let offsetX = 0;
    let offsetY = 0;

    if (boxRatio > imgRatio) {
      renderWidth = rect.height * imgRatio;
      offsetX = (rect.width - renderWidth) / 2;
    } else {
      renderHeight = rect.width / imgRatio;
      offsetY = (rect.height - renderHeight) / 2;
    }

    const relX = e.clientX - rect.left - offsetX;
    const relY = e.clientY - rect.top - offsetY;

    if (relX < 0 || relX > renderWidth || relY < 0 || relY > renderHeight) {
      return;
    }

    const dispX = relX / renderWidth;
    const dispY = relY / renderHeight;
    const clickX = Math.round(dispX * canvas.width);
    const clickY = Math.round(dispY * canvas.height);

    let normX = dispX;
    let normY = dispY;
    if (rotationDeg === 90) {
      normX = dispY;
      normY = 1 - dispX;
    } else if (rotationDeg === 180) {
      normX = 1 - dispX;
      normY = 1 - dispY;
    } else if (rotationDeg === 270) {
      normX = 1 - dispY;
      normY = dispX;
    }

    if (toolMode === 'focus') {
      clearAfTimer();
      setFocusPoint({ x: clickX, y: clickY });
      setAfStatus('focusing');
      try {
        const res = await client.autoFocusAt(normX, normY);
        if (!res.superseded) {
          setAfStatus(res.focused ? 'ok' : 'fail');
          scheduleAfDismiss(2000);
        }
      } catch (err) {
        setAfStatus('fail');
        scheduleAfDismiss(2000);
        onError?.(err instanceof Error ? err.message : String(err));
      }
      return;
    }

    // Magnify tool: map normalized click to Sony [-1000..1000], accounting for current magnification viewport
    const factor = magEnabled && magRatio > 100 ? magRatio / 100 : 1;
    const curX = magEnabled ? (previewMagnification?.pos?.[0] ?? 0) : 0;
    const curY = magEnabled ? (previewMagnification?.pos?.[1] ?? 0) : 0;
    const sx = Math.max(-1000, Math.min(1000, Math.round(curX + (normX - 0.5) * (2000 / factor))));
    const sy = Math.max(-1000, Math.min(1000, Math.round(curY + (normY - 0.5) * (2000 / factor))));
    const nextFactor: 100 | 200 = !magEnabled ? 100 : 200;
    try {
      await client.setPreviewMagnification(nextFactor, sx, sy);
      scheduleMagReset(autoResetSec);
    } catch (err) {
      onError?.(err instanceof Error ? err.message : String(err));
    }
  };

  return (
    <section className="panel liveview-panel">
      <div className="panel-header">
        <div className="panel-title">
          <span>实时取景</span>
          <span className="badge">{streaming && pageVisible && connected ? `${fps} FPS` : '已暂停'}</span>
          {rotationDeg !== 0 && <span className="badge">{rotationDeg}°</span>}
        </div>
        <div className="toolbar-row">
          <button
            type="button"
            className={`btn btn-sm ${showGrid ? 'btn-active' : ''}`}
            onClick={() => setShowGrid((g) => !g)}
            title="切换三分网格"
            aria-label="切换三分网格"
          >
            网格线
          </button>
          <button
            type="button"
            className={`btn btn-sm ${peaking ? 'btn-active' : ''}`}
            onClick={() => setPeaking((p) => !p)}
            title="切换峰值对焦"
            aria-label="切换峰值对焦"
          >
            峰值对焦
          </button>
          <button
            type="button"
            className={`btn btn-sm ${showHistogram ? 'btn-active' : ''}`}
            onClick={() => setShowHistogram((h) => !h)}
            title="切换亮度直方图"
            aria-label="切换亮度直方图"
          >
            直方图
          </button>
          <button
            type="button"
            className={`btn btn-sm ${showTonalBlocks ? 'btn-active' : ''}`}
            onClick={() => setShowTonalBlocks((show) => !show)}
            aria-pressed={showTonalBlocks}
          >
            明暗块面
          </button>
          {showTonalBlocks && (
            <>
            <select
              className="select-input"
              aria-label="明暗块面分层方式"
              value={cieLightness ? 'cie' : 'fixed'}
              onChange={(event) => setCieLightness(event.currentTarget.value === 'cie')}
            >
              <option value="fixed">固定亮度</option>
              <option value="cie">CIE L*</option>
            </select>
            <select
              className="select-input"
              aria-label="明暗块面层数"
              value={tonalBlockLevels}
              onChange={(event) => setTonalBlockLevels(Number(event.currentTarget.value))}
            >
              {TONAL_BLOCK_LEVELS.map((levels) => <option key={levels} value={levels}>{levels} 层</option>)}
            </select>
            </>
          )}
          <button
            type="button"
            className={`btn btn-sm ${streaming ? 'btn-active' : ''}`}
            onClick={() => {
              setFrameError(null);
              setStreaming((s) => !s);
            }}
            aria-pressed={streaming}
            title={streaming ? '暂停取景' : '恢复取景'}
            aria-label={streaming ? '暂停取景' : '恢复取景'}
          >
            {streaming ? (
              <svg
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="currentColor"
                aria-hidden="true"
              >
                <rect x="6" y="4" width="4" height="16" rx="1" />
                <rect x="14" y="4" width="4" height="16" rx="1" />
              </svg>
            ) : (
              <svg
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="currentColor"
                aria-hidden="true"
              >
                <polygon points="6 4 20 12 6 20 6 4" />
              </svg>
            )}
          </button>
          <button
            type="button"
            className={`btn btn-sm ${lvMode === 'jpg' ? 'btn-active' : ''}`}
            onClick={() => {
              setFrameError(null);
              setLvMode((m) => (m === 'mjpeg' ? 'jpg' : 'mjpeg'));
            }}
            aria-pressed={lvMode === 'jpg'}
            title={
              lvMode === 'jpg'
                ? 'Single-frame JPEG mode (click to switch to MJPEG stream)'
                : 'MJPEG stream mode (click to switch to single-frame JPEG)'
            }
            aria-label={
              lvMode === 'jpg'
                ? 'Single-frame JPEG mode, switch to MJPEG stream'
                : 'MJPEG stream mode, switch to single-frame JPEG'
            }
          >
            <svg
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <rect x="3" y="5" width="18" height="14" rx="2" />
              <circle cx="8.5" cy="10" r="1.5" />
              <path d="M21 16l-5-5-6 6-3-3-4 4" />
            </svg>
          </button>
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => rotateBy(270)}
            title="向左旋转 90°"
            aria-label="向左旋转 90°"
          >
            ↺
          </button>
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => rotateBy(90)}
            title="向右旋转 90°"
            aria-label="向右旋转 90°"
          >
            ↻
          </button>
        </div>
      </div>

      <div className="liveview-stage">
        <canvas
          ref={canvasRef}
          className="liveview-canvas"
          style={{ cursor: 'crosshair' }}
          onClick={(e) => void handleCanvasClick(e)}
          title={
            toolMode === 'focus'
              ? '点击画面选择对焦位置'
              : '点击画面选择放大位置'
          }
        />
        {showHistogram && connected && streaming && !frameError && (
          <canvas
            ref={histCanvasRef}
            width={160}
            height={56}
            className="histogram-overlay"
          />
        )}
        {(!connected || frameError) && (
          <div className="liveview-placeholder">
            {!connected ? '相机未连接' : frameError}
          </div>
        )}
        <button
          type="button"
          className="shutter-fab"
          disabled={!connected || capturing || stackMode}
          onClick={onCapture}
          title="拍摄"
          aria-label="拍摄"
        >
          <span className="shutter-inner" />
        </button>
      </div>

      {captureMessage && <p className="capture-feedback" role="status">{captureMessage}</p>}
      <div className="toolbar-row" style={{ marginTop: 8, justifyContent: 'space-between', alignItems: 'center' }}>
        <div className="toolbar-row">
          <button
            type="button"
            className={`btn btn-sm ${toolMode === 'focus' ? 'btn-active' : ''}`}
            disabled={!connected || stackMode}
            onClick={() => {
              setToolMode('focus');
              if (magEnabled) {
                void client.stopPreviewMagnification().catch(() => {});
              }
            }}
            title="点击画面临时对焦"
          >
            🎯 对焦
          </button>
          <button
            type="button"
            className={`btn btn-sm ${toolMode === 'magnify' ? 'btn-active' : ''}`}
            disabled={!connected}
            onClick={() => setToolMode('magnify')}
            title="点击画面放大（5.9× / 11.7×）"
          >
            🔍 放大
          </button>
          <button
            type="button"
            className="btn btn-sm"
            disabled={!connected || !magEnabled}
            onClick={() => {
              clearMagResetTimer();
              void client.stopPreviewMagnification().catch((err) => {
                onError?.(err instanceof Error ? err.message : String(err));
              });
            }}
            title="还原取景倍率"
          >
            ↩ 复位
          </button>
          <select
            className="btn btn-sm"
            value={autoResetSec}
            onChange={(e) => {
              const sec = Number(e.target.value);
              setAutoResetSec(sec);
              try {
                localStorage.setItem('pmca_mag_auto_reset_sec', String(sec));
              } catch {}
            }}
            title="Auto-reset magnification to 1x after N seconds"
            aria-label="Auto-reset magnification delay"
          >
            <option value={0}>自动复位：关闭</option>
            <option value={3}>3 秒后复位</option>
            <option value={5}>5 秒后复位</option>
            <option value={10}>10 秒后复位</option>
          </select>
        </div>
        <div className="toolbar-row" style={{ gap: 6 }}>
          <span className="badge">{magLabel}</span>
          {afStatus !== 'idle' && (
            <span className="badge">
              {afStatus === 'focusing' ? 'AF...' : afStatus === 'ok' ? 'AF OK' : 'AF Fail'}
            </span>
          )}
        </div>
      </div>
    </section>
  );
}

function findHeaderEnd(buf: Uint8Array): number {
  for (let i = 0; i <= buf.length - 4; i++) {
    if (
      buf[i] === 13 &&
      buf[i + 1] === 10 &&
      buf[i + 2] === 13 &&
      buf[i + 3] === 10
    ) {
      return i;
    }
  }
  return -1;
}

function drawGrid(ctx: CanvasRenderingContext2D, w: number, h: number) {
  ctx.save();
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.28)';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(w / 3, 0);
  ctx.lineTo(w / 3, h);
  ctx.moveTo((2 * w) / 3, 0);
  ctx.lineTo((2 * w) / 3, h);
  ctx.moveTo(0, h / 3);
  ctx.lineTo(w, h / 3);
  ctx.moveTo(0, (2 * h) / 3);
  ctx.lineTo(w, (2 * h) / 3);
  ctx.stroke();
  ctx.restore();
}

function drawHistogram(canvas: HTMLCanvasElement, data: Uint8ClampedArray) {
  const bins = new Uint32Array(64);
  for (let i = 0; i < data.length; i += 16) {
    const luma = (data[i] * 77 + data[i + 1] * 150 + data[i + 2] * 29) >> 8;
    bins[luma >> 2] += 1;
  }
  let max = 1;
  for (let i = 0; i < 64; i++) {
    if (bins[i] > max) max = bins[i];
  }
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const { width, height } = canvas;
  ctx.clearRect(0, 0, width, height);
  ctx.fillStyle = 'rgba(13, 17, 23, 0.68)';
  ctx.fillRect(0, 0, width, height);
  ctx.fillStyle = 'rgba(220, 235, 255, 0.85)';
  const barW = width / 64;
  for (let i = 0; i < 64; i++) {
    const barH = (bins[i] / max) * (height - 6);
    ctx.fillRect(i * barW, height - barH, Math.max(1, barW - 0.5), barH);
  }
}

function applyPreviewEffects(data: Uint8ClampedArray, w: number, h: number, levels: number, peaking: boolean, cieLightness: boolean) {
  const threshold = 64;
  const luma = peaking ? new Uint8Array(w * h) : null;
  const cie = cieLightness ? CIE_BANDS.get(levels) : undefined;
  for (let p = 0, i = 0; p < data.length; p += 4, i++) {
    const r = data[p], g = data[p + 1], b = data[p + 2];
    if (luma) luma[i] = (r * 77 + g * 150 + b * 29) >> 8;
    if (cie) {
      const y = 0.2126 * SRGB_LINEAR[r] + 0.7152 * SRGB_LINEAR[g] + 0.0722 * SRGB_LINEAR[b];
      let band = 0;
      while (band < cie.limits.length && y >= cie.limits[band]) band++;
      data[p] = data[p + 1] = data[p + 2] = cie.grays[band];
    } else if (levels > 0) {
      // Match the display-referred brightness bands used in the phone's photo preview.
      const luminance = Math.round((2126 * r + 7152 * g + 722 * b) / 10000);
      const band = (luminance * levels) >> 8;
      const gray = Math.round((band * 255) / (levels - 1));
      data[p] = data[p + 1] = data[p + 2] = gray;
    }
  }
  if (!luma) return;
  for (let y = 1; y < h - 1; y++) {
    const row = y * w;
    for (let x = 1; x < w - 1; x++) {
      const idx = row + x;
      const gx = Math.abs(luma[idx + 1] - luma[idx - 1]);
      const gy = Math.abs(luma[idx + w] - luma[idx - w]);
      if (gx + gy > threshold) {
        const p = idx * 4;
        data[p] = 255;
        data[p + 1] = 48;
        data[p + 2] = 48;
      }
    }
  }
}

function drawFocusBox(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cy: number,
  boxW: number,
  boxH: number,
  zoomText: string,
  color = '#7ee787',
) {
  ctx.save();
  const left = cx - boxW / 2;
  const top = cy - boxH / 2;

  // Box border with outer glow
  ctx.shadowColor = 'rgba(0, 0, 0, 0.85)';
  ctx.shadowBlur = 4;
  ctx.strokeStyle = color;
  ctx.lineWidth = 1.5;
  ctx.strokeRect(left, top, boxW, boxH);

  // Corner bracket accents
  const cornerLen = Math.max(6, Math.min(boxW, boxH) * 0.22);
  ctx.lineWidth = 2.5;
  ctx.beginPath();
  // Top-left
  ctx.moveTo(left, top + cornerLen);
  ctx.lineTo(left, top);
  ctx.lineTo(left + cornerLen, top);
  // Top-right
  ctx.moveTo(left + boxW - cornerLen, top);
  ctx.lineTo(left + boxW, top);
  ctx.lineTo(left + boxW, top + cornerLen);
  // Bottom-left
  ctx.moveTo(left, top + boxH - cornerLen);
  ctx.lineTo(left, top + boxH);
  ctx.lineTo(left + cornerLen, top + boxH);
  // Bottom-right
  ctx.moveTo(left + boxW - cornerLen, top + boxH);
  ctx.lineTo(left + boxW, top + boxH);
  ctx.lineTo(left + boxW, top + boxH - cornerLen);
  ctx.stroke();

  // Center crosshair
  const chLen = 5;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(cx - chLen, cy);
  ctx.lineTo(cx + chLen, cy);
  ctx.moveTo(cx, cy - chLen);
  ctx.lineTo(cx, cy + chLen);
  ctx.stroke();

  // Badge label above box
  ctx.font = 'bold 10px monospace';
  const labelW = Math.max(34, Math.ceil(ctx.measureText(zoomText).width) + 8);
  ctx.fillStyle = 'rgba(13, 17, 23, 0.8)';
  ctx.fillRect(left, Math.max(0, top - 16), labelW, 14);
  ctx.fillStyle = color;
  ctx.fillText(zoomText, left + 4, Math.max(11, top - 5));

  ctx.restore();
}
