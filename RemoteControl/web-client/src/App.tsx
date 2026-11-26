import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  CameraClient,
  type CameraEvent,
  type CameraState,
  getDefaultCameraUrl,
} from './api/cameraClient';
import {
  isDirectoryPickerSupported,
  pickSaveDirectory,
  readFileFromDirectory,
  saveBlobToDirectory,
  triggerBrowserDownload,
} from './api/localSave';
import {
  backendStackResultUrl,
  createBackendStackSession,
  deleteBackendStackSession,
  fetchHostCapabilities,
  getBackendStackStatus,
  grabLatestToBackendStack,
  type HostCapabilities,
  triggerBackendStackMerge,
} from './api/hostBackend';
import { AutoSavePanel } from './components/AutoSavePanel';
import { EventsPanel, type LoggedCameraEvent } from './components/EventsPanel';
import { ExposureControl } from './components/ExposureControl';
import { FocusControl } from './components/FocusControl';
import { LiveViewPanel } from './components/LiveViewPanel';
import { PhotoBrowser } from './components/PhotoBrowser';
import { PhotoPreviewModal } from './components/PhotoPreviewModal';
import { RpcInspector } from './components/RpcInspector';

type TabKey = 'control' | 'photos' | 'events' | 'rpc';

function applyEventsToState(
  prev: CameraState | null,
  events: CameraEvent[],
): { next: CameraState | null; needFullRefresh: boolean } {
  let needFullRefresh = false;
  if (!prev) {
    return { next: prev, needFullRefresh: true };
  }
  const next: CameraState = { ...prev };
  for (const ev of events) {
    const d = ev.data ?? {};
    switch (ev.type) {
      case 'FocusDrive':
        next.focus = d as CameraState['focus'];
        break;
      case 'ApertureChange':
        next.aperture = d as CameraState['aperture'];
        break;
      case 'ShutterSpeedChange':
        next.shutter = d as CameraState['shutter'];
        break;
      case 'AutoISOSensitivity':
        if (typeof d.iso === 'number') next.autoIso = d.iso;
        break;
      case 'ControlSettings':
        if (typeof d.isoSetting === 'number') next.isoSetting = d.isoSetting;
        if (typeof d.sceneMode === 'string') next.sceneMode = d.sceneMode;
        if (typeof d.rawRecMode === 'string') next.rawRecMode = d.rawRecMode;
        if (d.exposureCompensation) next.exposureCompensation = d.exposureCompensation as CameraState['exposureCompensation'];
        if (d.aperture) next.aperture = d.aperture as CameraState['aperture'];
        if (d.shutter) next.shutter = d.shutter as CameraState['shutter'];
        if (d.focusSettings) next.focusSettings = d.focusSettings as CameraState['focusSettings'];
        break;
      case 'ZoomChange':
        next.zoom = d as CameraState['zoom'];
        break;
      case 'FocalLengthChange':
        if (typeof d.focalLength === 'number') next.focalLength = d.focalLength;
        break;
      case 'PowerZoom':
        if (typeof d.status === 'number') next.powerZoom = d.status;
        break;
      case 'PreviewMagnification':
        next.previewMagnification = d as CameraState['previewMagnification'];
        break;
      case 'CaptureStart':
      case 'CaptureEnd':
        if (typeof d.capturing === 'boolean') next.capturing = d.capturing;
        break;
      case 'Shutter':
        if (typeof d.status === 'number') next.lastShutterStatus = d.status;
        break;
      case 'StoreImageComplete':
        next.lastStoreImage = d as CameraState['lastStoreImage'];
        break;
      case 'SettingChanged':
      case 'EquipmentChange':
        needFullRefresh = true;
        break;
    }
    next.seq = Math.max(next.seq ?? 0, ev.seq);
  }
  return { next, needFullRefresh };
}

function getEventsClientId(): string {
  const fallback = `ev-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  try {
    const existing = window.sessionStorage.getItem('pmca_ev_cid');
    if (existing) return existing;
    window.sessionStorage.setItem('pmca_ev_cid', fallback);
    return fallback;
  } catch {
    return fallback;
  }
}

export function App() {
  const [urlInput, setUrlInput] = useState<string>(() => getDefaultCameraUrl());
  const [activeUrl, setActiveUrl] = useState<string>(() => getDefaultCameraUrl());
  const client = useMemo(() => new CameraClient(activeUrl), [activeUrl]);

  const [connected, setConnected] = useState(false);
  const [manualDisconnected, setManualDisconnected] = useState(false);
  const [state, setState] = useState<CameraState | null>(null);
  const [stackMode, setStackMode] = useState(false);
  const [events, setEvents] = useState<LoggedCameraEvent[]>([]);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [captureMessage, setCaptureMessage] = useState('');
  const [capturePending, setCapturePending] = useState(false);
  const captureTimer = useRef<number | null>(null);
  useEffect(() => () => {
    if (captureTimer.current !== null) window.clearTimeout(captureTimer.current);
  }, [client]);
  const [tab, setTab] = useState<TabKey>('control');

  const [autoSaveEnabled, setAutoSaveEnabled] = useState<boolean>(() => {
    try {
      return localStorage.getItem('pmca_autosave_enabled') === 'true';
    } catch {
      return false;
    }
  });
  const [saveDirName, setSaveDirName] = useState<string | null>(null);
  const [savedCount, setSavedCount] = useState<number>(0);
  const [lastSavedFile, setLastSavedFile] = useState<string | null>(null);
  const [savingStatus, setSavingStatus] = useState<string | null>(null);
  const [hostCaps, setHostCaps] = useState<HostCapabilities>({
    backend: false,
    openfocus: {
      available: false,
      reason: 'Checking host backend...',
    },
  });
  const [autoMerge, setAutoMerge] = useState<boolean>(() => {
    try {
      return localStorage.getItem('pmca_automerge_enabled') === 'true';
    } catch {
      return false;
    }
  });
  const [mergeStatus, setMergeStatus] = useState<string | null>(null);
  const [previewPhoto, setPreviewPhoto] = useState<{ url: string; name: string; downloadUrl?: string } | null>(null);

  const seqRef = useRef<number>(0);
  const dirHandleRef = useRef<FileSystemDirectoryHandle | null>(null);
  const latestSavedRef = useRef<{ blob: Blob; name: string } | null>(null);
  const lastSavedFileRef = useRef<string | null>(null);
  const activeSavePromiseRef = useRef<Promise<void> | null>(null);
  const openedTabUrlsRef = useRef<string[]>([]);
  const activeUrlRef = useRef<string>(activeUrl);
  activeUrlRef.current = activeUrl;

  useEffect(() => {
    return () => {
      for (const u of openedTabUrlsRef.current) {
        URL.revokeObjectURL(u);
      }
      openedTabUrlsRef.current = [];
    };
  }, []);

  useEffect(() => {
    if (__CAMERA_EMBEDDED__) return;
    void fetchHostCapabilities().then(setHostCaps);
  }, []);
  const autoSaveRef = useRef<boolean>(false);
  autoSaveRef.current = autoSaveEnabled;

  const refreshState = useCallback(async (): Promise<boolean> => {
    try {
      const s = await client.getState();
      setState(s);
      if (typeof s.seq === 'number') {
        if (s.seq + 10 < seqRef.current) {
          seqRef.current = s.seq;
          handledFilesRef.current.clear();
        } else {
          seqRef.current = Math.max(seqRef.current, s.seq);
        }
      }
      setConnected(true);
      setErrorMsg(null);
      return true;
    } catch (err) {
      setConnected(false);
      setErrorMsg(err instanceof Error ? err.message : String(err));
      return false;
    }
  }, [client]);

  const handledFilesRef = useRef<Set<string>>(new Set());
  const stackSessionRef = useRef<{ baseName: string; nextIndex: number } | null>(null);
  const backendMergeSessionRef = useRef<{ sessionId: string; pendingGrabs: Set<Promise<void>> } | null>(null);
  const stackShotWaitersRef = useRef<Array<() => void>>([]);
  const saveQueueRef = useRef<Array<{ name: string; url: string; resolve?: () => void }>>([]);
  const deferredStackDownloads = useRef<Array<{ name: string; url: string }>>([]);
  const isProcessingQueueRef = useRef<boolean>(false);

  const processSaveQueue = useCallback(async () => {
    if (isProcessingQueueRef.current) return;
    isProcessingQueueRef.current = true;
    while (saveQueueRef.current.length > 0) {
      const task = saveQueueRef.current.shift();
      if (!task) break;
      setSavingStatus(`Saving ${task.name}...`);
      try {
        const res = await fetch(task.url);
        if (!res.ok) throw new Error(`Download failed: HTTP ${res.status}`);
        const blob = await res.blob();
        if (dirHandleRef.current) {
          await saveBlobToDirectory(dirHandleRef.current, task.name, blob);
        } else {
          triggerBrowserDownload(task.name, blob);
        }
        latestSavedRef.current = { blob, name: task.name };
        lastSavedFileRef.current = task.name;
        setSavedCount((c) => c + 1);
        setLastSavedFile(task.name);
      } catch (err) {
        setErrorMsg(err instanceof Error ? err.message : String(err));
      } finally {
        task.resolve?.();
      }
    }
    setSavingStatus(null);
    isProcessingQueueRef.current = false;
  }, []);

  const queueSavePhoto = useCallback(
    (task: { name: string; url: string }): Promise<void> => {
      const promise = new Promise<void>((resolve) => {
        saveQueueRef.current.push({ ...task, resolve });
        void processSaveQueue();
      });
      activeSavePromiseRef.current = promise;
      void promise.finally(() => {
        if (activeSavePromiseRef.current === promise) {
          activeSavePromiseRef.current = null;
        }
      });
      return promise;
    },
    [processSaveQueue],
  );

  const handlePickDirectory = useCallback(async () => {
    try {
      const handle = await pickSaveDirectory();
      dirHandleRef.current = handle;
      setSaveDirName(handle.name);
      setAutoSaveEnabled(true);
      try {
        localStorage.setItem('pmca_autosave_enabled', 'true');
      } catch {}
      setErrorMsg(null);
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      setErrorMsg(err instanceof Error ? err.message : String(err));
    }
  }, []);

  const nextCaptureFileName = useCallback(() => {
    const stack = stackSessionRef.current;
    if (stack) {
      stack.nextIndex += 1;
      return `${stack.baseName}_${String(stack.nextIndex).padStart(3, '0')}.jpg`;
    }
    const d = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    return `capture_${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}.jpg`;
  }, []);

  const handleCaptureEvent = useCallback(
    (ev: CameraEvent) => {
      if (ev.type !== (__CAMERA_EMBEDDED__ ? 'StoreImageComplete' : 'Jpeg')) return;

      const file = ev.data?.FileName;
      const directory = ev.data?.DirectoryName;
      if (__CAMERA_EMBEDDED__ && (typeof file !== 'string' || !file)) return;
      const captureName = typeof file === 'string' ? file.replace(/\.(jpe?g|arw)$/i, '') : undefined;
      const dedupKey = captureName ? JSON.stringify([directory, captureName]) : `jpeg_${ev.seq}`;
      if (handledFilesRef.current.has(dedupKey)) return;
      if (handledFilesRef.current.size > 500) {
        handledFilesRef.current.clear();
      }
      handledFilesRef.current.add(dedupKey);

      const waiter = stackShotWaitersRef.current.shift();

      const mergeSession = __CAMERA_EMBEDDED__ ? null : backendMergeSessionRef.current;
      if (mergeSession) {
        const grabTask = grabLatestToBackendStack(mergeSession.sessionId, activeUrlRef.current)
          .then(() => undefined)
          .catch((err) => {
            setErrorMsg(err instanceof Error ? err.message : String(err));
          });
        mergeSession.pendingGrabs.add(grabTask);
        void grabTask.finally(() => {
          mergeSession.pendingGrabs.delete(grabTask);
          waiter?.();
        });
        return;
      }

      if (!autoSaveRef.current) {
        waiter?.();
        return;
      }

      const hasStoredPath = typeof file === 'string' && /^[A-Z0-9_-]+\.(jpe?g|arw)$/i.test(file)
        && typeof directory === 'string' && /(?:^|[\\/])\d{3}[A-Z0-9_-]+[\\/]*$/i.test(directory);
      const name = nextCaptureFileName();
      const task = {
        name: hasStoredPath && /\.arw$/i.test(file) ? name.replace(/\.jpg$/, '.arw') : name,
        url: hasStoredPath ? client.storedPhotoUrl(directory, file) : client.latestPhotoUrl(Date.now()),
      };
      if (__CAMERA_EMBEDDED__ && waiter && hasStoredPath) {
        deferredStackDownloads.current.push(task);
        waiter();
        return;
      }
      const saveTask = queueSavePhoto(task);
      if (waiter) {
        void saveTask.finally(() => {
          waiter();
        });
      }
    },
    [client, nextCaptureFileName, queueSavePhoto],
  );

  const captureStackShot = useCallback(
    async (triggerShot: () => Promise<unknown>, signal?: AbortSignal) => {
      if (signal?.aborted) {
        throw new DOMException('Aborted', 'AbortError');
      }

      let done = false;
      let timerId: number | null = null;
      let finishWaiter: () => void = () => {};
      let cancelWaiter: (err: unknown) => void = () => {};

      const waitForPhoto = new Promise<void>((resolve, reject) => {
        const onAbort = () => {
          if (done) return;
          done = true;
          cleanup();
          reject(new DOMException('Aborted', 'AbortError'));
        };
        const cleanup = () => {
          if (timerId !== null) {
            window.clearTimeout(timerId);
            timerId = null;
          }
          const idx = stackShotWaitersRef.current.indexOf(finishWaiter);
          if (idx >= 0) {
            stackShotWaitersRef.current.splice(idx, 1);
          }
          if (signal) {
            signal.removeEventListener('abort', onAbort);
          }
        };
        finishWaiter = () => {
          if (done) return;
          done = true;
          cleanup();
          resolve();
        };
        cancelWaiter = (err: unknown) => {
          if (done) return;
          done = true;
          cleanup();
          reject(err);
        };

        stackShotWaitersRef.current.push(finishWaiter);
        timerId = window.setTimeout(() => {
          if (__CAMERA_EMBEDDED__) cancelWaiter(new Error('等待照片写卡超时，已停止包围拍摄'));
          else finishWaiter();
        }, 20000);
        if (signal) {
          signal.addEventListener('abort', onAbort, { once: true });
        }
      });

      const trigger = Promise.resolve().then(triggerShot).catch((err) => {
        cancelWaiter(err);
        throw err;
      });
      await Promise.all([trigger, waitForPhoto]);
    },
    [],
  );

  const handleStackSessionStart = useCallback(
    async (baseName: string, useAutoMerge: boolean) => {
      if (!useAutoMerge) return;
      await createBackendStackSession(baseName, activeUrlRef.current);
      backendMergeSessionRef.current = {
        sessionId: baseName,
        pendingGrabs: new Set(),
      };
      setMergeStatus(null);
    },
    [],
  );

  const handleStackSessionEnd = useCallback(
    async (baseName: string, useAutoMerge: boolean, aborted: boolean, expectedShots: number) => {
      if (!useAutoMerge || !backendMergeSessionRef.current) return;
      const session = backendMergeSessionRef.current;

      if (aborted) {
        if (session.pendingGrabs.size > 0) {
          await Promise.allSettled(Array.from(session.pendingGrabs));
        }
        if (backendMergeSessionRef.current === session) {
          backendMergeSessionRef.current = null;
        }
        await deleteBackendStackSession(baseName);
        setMergeStatus(null);
        return;
      }

      const mergedFileName = `${baseName}.jpg`;
      try {
        setMergeStatus(`Verifying frames (0/${expectedShots})...`);
        setSavingStatus(`Verifying frames...`);

        // Wait up to 10s for final Jpeg SSE events and in-flight backend grabs to finish
        const deadline = Date.now() + 10000;
        let currentFrames = 0;
        while (true) {
          if (session.pendingGrabs.size > 0) {
            await Promise.allSettled(Array.from(session.pendingGrabs));
          }
          const stCheck = await getBackendStackStatus(baseName);
          currentFrames = stCheck.frameCount;
          setMergeStatus(`Verifying frames (${currentFrames}/${expectedShots})...`);
          if (currentFrames >= expectedShots && session.pendingGrabs.size === 0) {
            break;
          }
          if (Date.now() >= deadline) {
            break;
          }
          await new Promise((r) => window.setTimeout(r, 300));
        }

        if (backendMergeSessionRef.current === session) {
          backendMergeSessionRef.current = null;
        }

        if (currentFrames < expectedShots) {
          throw new Error(
            `Incomplete stack: expected ${expectedShots} photos, got ${currentFrames}. Skipping merge.`,
          );
        }

        setMergeStatus(`Merging ${currentFrames} frames with OpenFocus...`);
        setSavingStatus('Merging with OpenFocus...');
        await triggerBackendStackMerge(baseName, expectedShots);

        while (true) {
          await new Promise((r) => window.setTimeout(r, 1000));
          const st = await getBackendStackStatus(baseName);
          if (st.status === 'done') {
            await queueSavePhoto({
              name: mergedFileName,
              url: backendStackResultUrl(baseName),
            });
            setMergeStatus(`Merged ${currentFrames} frames (1 photo saved)`);
            break;
          }
          if (st.status === 'error') {
            throw new Error(st.error || 'OpenFocus merge failed');
          }
        }
      } catch (err) {
        if (backendMergeSessionRef.current === session) {
          backendMergeSessionRef.current = null;
        }
        const msg = err instanceof Error ? err.message : String(err);
        setErrorMsg(msg);
        setMergeStatus(`Merge error: ${msg}`);
        setSavingStatus(null);
      } finally {
        await deleteBackendStackSession(baseName);
      }
    },
    [queueSavePhoto],
  );

  const eventsCidRef = useRef<string>(getEventsClientId());

  useEffect(() => {
    const onPageHide = () => {
      client.closeEventsStream(eventsCidRef.current);
    };
    window.addEventListener('pagehide', onPageHide);
    return () => {
      window.removeEventListener('pagehide', onPageHide);
    };
  }, [client]);

  // Initial state fetch + SSE /api/events/stream (kept open across tab switches so captures are never missed)
  useEffect(() => {
    if (manualDisconnected) {
      setConnected(false);
      return;
    }
    let cancelled = false;
    let eventSource: EventSource | null = null;
    let retryTimer: number | null = null;

    const scheduleRetry = () => {
      if (cancelled || retryTimer !== null) return;
      retryTimer = window.setTimeout(() => {
        retryTimer = null;
        void start();
      }, 1500);
    };

    const handleIncomingEvents = async (incoming: CameraEvent[]) => {
      if (cancelled || incoming.length === 0) return;
      const now = Date.now();
      const logged: LoggedCameraEvent[] = incoming.map((ev) => ({
        ...ev,
        receivedAt: now,
      }));
      setEvents((prev) => [...logged.slice().reverse(), ...prev].slice(0, 200));
      let needFullRefresh = false;
      setState((prev) => {
        const applied = applyEventsToState(prev, incoming);
        needFullRefresh = applied.needFullRefresh;
        return applied.next;
      });
      for (const ev of incoming) {
        const photoReady = ev.type === 'Jpeg' ||
          (ev.type === 'StoreImageComplete' && typeof ev.data?.FileName === 'string' && ev.data.FileName.length > 0);
        if (captureTimer.current !== null && ev.type === 'CaptureEnd') {
          setCaptureMessage('曝光结束，等待相机写卡…');
        }
        if (captureTimer.current !== null && photoReady) {
          window.clearTimeout(captureTimer.current);
          captureTimer.current = null;
          setCapturePending(false);
          setCaptureMessage(ev.type === 'Jpeg' ? '已收到照片预览' : '相机已回报写卡，请在照片列表确认');
        }
        if (ev.type === 'Jpeg' || ev.type === 'StoreImageComplete') {
          handleCaptureEvent(ev);
        }
      }
      if (needFullRefresh) {
        await refreshState();
      }
    };

    const start = async () => {
      const ok = await refreshState();
      if (cancelled) return;
      if (!ok) {
        scheduleRetry();
        return;
      }

      const es = new EventSource(client.eventsStreamUrl(seqRef.current, eventsCidRef.current));
      eventSource = es;

      es.onopen = () => {
        if (cancelled) return;
        setConnected(true);
      };

      es.onmessage = (msg) => {
        if (cancelled || !msg.data) return;
        try {
          const ev = JSON.parse(msg.data) as CameraEvent;
          if (typeof ev.seq === 'number' && ev.seq > seqRef.current) {
            seqRef.current = ev.seq;
          }
          setConnected(true);
          void handleIncomingEvents([ev]);
        } catch {
          // Ignore malformed event payloads
        }
      };

      es.onerror = () => {
        if (cancelled) return;
        setConnected(false);
        es.close();
        if (eventSource === es) {
          eventSource = null;
        }
        scheduleRetry();
      };
    };

    void start();

    return () => {
      cancelled = true;
      if (retryTimer !== null) {
        window.clearTimeout(retryTimer);
      }
      if (eventSource) {
        eventSource.close();
      }
      client.closeEventsStream(eventsCidRef.current);
    };
  }, [client, handleCaptureEvent, refreshState, manualDisconnected]);

  const handleConnectSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (connected) {
      setManualDisconnected(true);
      setConnected(false);
      setState(null);
      return;
    }
    const cleaned = urlInput.trim().replace(/\/+$/, '');
    if (!cleaned) return;
    setManualDisconnected(false);
    client.setBaseUrl(cleaned);
    setActiveUrl(cleaned);
    seqRef.current = 0;
  };

  const handleCapture = async () => {
    if (stackMode || captureTimer.current !== null) return;
    setCaptureMessage('正在请求拍摄…');
    setCapturePending(true);
    captureTimer.current = window.setTimeout(() => {
      captureTimer.current = null;
      setCapturePending(false);
      setCaptureMessage('未收到照片确认。请检查相机屏幕和存储卡，再决定是否重试。');
    }, 15000);
    try {
      await client.takePicture();
      if (captureTimer.current !== null) {
        setCaptureMessage((message) => message === '正在请求拍摄…' ? '已发送快门指令，等待相机回报…' : message);
      }
    } catch (err) {
      if (captureTimer.current === null) return;
      window.clearTimeout(captureTimer.current);
      captureTimer.current = null;
      setCapturePending(false);
      setCaptureMessage(`拍摄请求失败：${err instanceof Error ? err.message : String(err)}`);
    }
  };

  const openBlobInNewTab = useCallback(
    async (loader: () => Promise<{ blob: Blob; name: string }>) => {
      const popup = window.open('', '_blank');
      if (popup) {
        try {
          popup.document.open();
          popup.document.write(
            '<!doctype html><title>Loading photo...</title><body style="margin:0;background:#0d1117;color:#c9d1d9;display:flex;align-items:center;justify-content:center;height:100vh;font-family:system-ui,sans-serif">Loading photo...</body>',
          );
          popup.document.close();
        } catch {
          // ignore cross-context write issues
        }
      }
      try {
        const { blob, name } = await loader();
        const url = URL.createObjectURL(blob);
        openedTabUrlsRef.current.push(url);
        if (openedTabUrlsRef.current.length > 10) {
          const old = openedTabUrlsRef.current.shift();
          if (old) URL.revokeObjectURL(old);
        }
        if (popup && !popup.closed) {
          popup.location.replace(url);
        } else {
          setPreviewPhoto({ url, name });
        }
      } catch (err) {
        if (popup && !popup.closed) {
          popup.close();
        }
        setErrorMsg(err instanceof Error ? err.message : String(err));
      }
    },
    [],
  );

  const handleOpenSavedPhoto = useCallback(() => {
    void openBlobInNewTab(async () => {
      if (activeSavePromiseRef.current) {
        await activeSavePromiseRef.current;
      }
      const name = lastSavedFileRef.current;
      if (dirHandleRef.current && name) {
        const file = await readFileFromDirectory(dirHandleRef.current, name);
        return { blob: file, name };
      }
      if (latestSavedRef.current) {
        return { blob: latestSavedRef.current.blob, name: latestSavedRef.current.name };
      }
      throw new Error('No local auto-saved photo in this session yet');
    });
  }, [openBlobInNewTab]);

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <span className={`status-dot ${connected ? 'dot-ok' : 'dot-off'}`} />
          <span className="brand-title">Sony PMCA WebControl</span>
        </div>

        <nav className="tabs" aria-label="Primary views">
          {(
            [
              { id: 'control', label: '相机控制' },
              { id: 'photos', label: '照片' },
              { id: 'events', label: '事件' },
              { id: 'rpc', label: 'RPC 调试' },
            ] as const
          ).map(({ id, label }) => (
            <button
              key={id}
              type="button"
              className={`tab-btn ${tab === id ? 'tab-active' : ''}`}
              onClick={() => {
                if (id === 'photos' && __CAMERA_EMBEDDED__ && /\bAlphaSyncFiles\/1\b/.test(navigator.userAgent)) {
                  window.location.assign(new URL('native/files', window.location.href));
                } else {
                  setTab(id);
                }
              }}
            >
              {label}
            </button>
          ))}
        </nav>

        {!__CAMERA_EMBEDDED__ && (
          <form className="connect-form" onSubmit={handleConnectSubmit}>
            <input
              type="text"
              className="input-text url-input"
              value={urlInput}
              disabled={connected}
              onChange={(e) => setUrlInput(e.target.value)}
              placeholder="http://192.168.0.250:8080"
              aria-label="Camera base URL"
              title="Camera HTTP server URL"
            />
            <button
              type="submit"
              className={`btn btn-sm ${connected ? 'btn-danger' : 'btn-primary'}`}
              title={connected ? 'Disconnect from camera' : 'Connect to camera'}
            >
              {connected ? '断开连接' : '连接'}
            </button>
          </form>
        )}
      </header>

      {errorMsg && (
        <div className="error-banner">
          <span>{errorMsg}</span>
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => setErrorMsg(null)}
            aria-label="关闭错误提示"
          >
            关闭
          </button>
        </div>
      )}

      <main className="main-content">
        {tab === 'control' && (
          <div className="control-layout">
            <LiveViewPanel
              stackMode={stackMode}
              client={client}
              connected={connected}
              onCapture={handleCapture}
              capturing={capturePending || Boolean(state?.capturing)}
              captureMessage={captureMessage}
              previewMagnification={state?.previewMagnification}
              onError={setErrorMsg}
            />
            <div className="side-column">
              <ExposureControl
                client={client}
                state={state}
                connected={connected}
                capturing={capturePending || Boolean(state?.capturing)}
                onError={setErrorMsg}
              />
              <FocusControl
                stackMode={stackMode}
                onStackModeChange={setStackMode}
                onFocusSettingsChanged={(focusSettings) => setState((prev) => prev && { ...prev, focusSettings })}
                client={client}
                state={state}
                connected={connected}
                onError={setErrorMsg}
                onStackSessionChange={(baseName) => {
                  stackSessionRef.current = baseName ? { baseName, nextIndex: 0 } : null;
                }}
                openFocusAvailable={!__CAMERA_EMBEDDED__ && hostCaps.openfocus.available}
                openFocusReason={hostCaps.openfocus.reason}
                autoMerge={autoMerge}
                hideAutoMerge={__CAMERA_EMBEDDED__}
                onAutoMergeChange={(checked) => {
                  setAutoMerge(checked);
                  try {
                    localStorage.setItem('pmca_automerge_enabled', String(checked));
                  } catch {}
                }}
                mergeStatus={mergeStatus}
                onStackSessionStart={__CAMERA_EMBEDDED__ ? undefined : handleStackSessionStart}
                onCaptureStackShot={captureStackShot}
                onStackSessionEnd={__CAMERA_EMBEDDED__ ? () => {
                  const downloads = deferredStackDownloads.current.splice(0);
                  for (const task of downloads) void queueSavePhoto(task);
                } : handleStackSessionEnd}
              />
              <AutoSavePanel
                enabled={autoSaveEnabled}
                onToggle={(next) => {
                  if (next && isDirectoryPickerSupported() && !dirHandleRef.current) {
                    void handlePickDirectory();
                  } else {
                    setAutoSaveEnabled(next);
                    try {
                      localStorage.setItem('pmca_autosave_enabled', String(next));
                    } catch {}
                  }
                }}
                dirName={saveDirName}
                onPickDirectory={() => void handlePickDirectory()}
                savedCount={savedCount}
                lastSavedFile={lastSavedFile}
                savingStatus={savingStatus}
                connected={connected}
                getLatestPhotoUrl={() => client.latestPhotoUrl()}
                onViewLatestPhoto={() => {
                  const now = Date.now();
                  setPreviewPhoto({
                    url: client.latestPhotoPreviewUrl(now),
                    downloadUrl: client.latestPhotoUrl(now),
                    name: '最新照片',
                  });
                }}
                onOpenSavedPhoto={handleOpenSavedPhoto}
              />
            </div>
          </div>
        )}

        {tab === 'photos' && (
          <PhotoBrowser
            rawRecMode={state?.rawRecMode}
            client={client}
            connected={connected}
            onError={setErrorMsg}
          />
        )}

        {tab === 'events' && (
          <EventsPanel events={events} onClear={() => setEvents([])} />
        )}

        {tab === 'rpc' && <RpcInspector client={client} connected={connected} />}
      </main>

      <PhotoPreviewModal
        url={previewPhoto?.url ?? null}
        title={previewPhoto?.name ?? '最新照片'}
        downloadUrl={previewPhoto?.downloadUrl}
        onClose={() => setPreviewPhoto(null)}
      />
    </div>
  );
}
