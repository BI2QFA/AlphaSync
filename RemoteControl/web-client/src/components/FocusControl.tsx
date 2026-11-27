import { useEffect, useRef, useState } from 'react';
import {
  type CameraClient,
  type CameraState,
  computeFocusStackPositions,
} from '../api/cameraClient';

interface FocusControlProps {
  client: CameraClient;
  state: CameraState | null;
  connected: boolean;
  onError: (msg: string) => void;
  onFocusSettingsChanged: (settings: NonNullable<CameraState['focusSettings']>) => void;
  stackMode: boolean;
  onStackModeChange: (active: boolean) => void;
  onStackSessionChange?: (baseName: string | null) => void;
  openFocusAvailable: boolean;
  openFocusReason: string;
  autoMerge: boolean;
  onAutoMergeChange: (checked: boolean) => void;
  hideAutoMerge?: boolean;
  mergeStatus?: string | null;
  onStackSessionStart?: (baseName: string, autoMerge: boolean) => Promise<void> | void;
  onCaptureStackShot?: (triggerShot: () => Promise<unknown>, signal?: AbortSignal) => Promise<void>;
  onStackSessionEnd?: (
    baseName: string,
    autoMerge: boolean,
    aborted: boolean,
    expectedShots: number,
  ) => Promise<void> | void;
}

function formatCaptureBaseName(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `capture_${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}

const FOCUS_MODE_LABELS: Record<string, string> = {
  auto: '自动对焦',
  'auto:af-s': '单次 AF（AF-S）',
  'auto:af-c': '连续 AF（AF-C）',
  'auto:af-a': '自动切换 AF（AF-A）',
  'auto:movie': '视频自动对焦',
  'continuous-picture': '连续 AF',
  dmf: '直接手动对焦（DMF）',
  manual: '手动对焦（MF）',
};

const FOCUS_AREA_LABELS: Record<string, string> = {
  wide: '广域',
  multi: '多点',
  local: '局部',
  spot: '点对焦',
  semiwide: '半广域',
  'semi-multi': '局部多点',
  touch: '触屏对焦',
  center: '中间',
  'flex-spot': '自由点',
  'center-spot': '中央点',
  'expand-flex-spot': '扩展自由点',
  zone: '区',
  tracking: '跟踪',
};

const FOCUS_SPOT_SIZE_LABELS: Record<string, string> = {
  small: '小（S）',
  midium: '中（M）',
  medium: '中（M）',
  large: '大（L）',
};

export function FocusControl({
  client,
  state,
  connected,
  onError,
  onFocusSettingsChanged,
  stackMode,
  onStackModeChange,
  onStackSessionChange,
  openFocusAvailable,
  openFocusReason,
  autoMerge,
  onAutoMergeChange,
  hideAutoMerge = false,
  mergeStatus,
  onStackSessionStart,
  onCaptureStackShot,
  onStackSessionEnd,
}: FocusControlProps) {
  const [busy, setBusy] = useState(false);
  const [settingBusy, setSettingBusy] = useState(false);
  const [settingMessage, setSettingMessage] = useState('');
  const focusSettings = state?.focusSettings;
  const priorFocusMode = useRef<string | undefined>(undefined);
  const readFocusSettings = async () => {
    setSettingBusy(true);
    setSettingMessage('');
    try {
      onFocusSettingsChanged(await client.getFocusSettings());
    } catch (err) {
      setSettingMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setSettingBusy(false);
    }
  };
  const changeFocusSetting = async (key: 'mode' | 'area' | 'spotSize', value: string) => {
    setSettingBusy(true);
    setSettingMessage('');
    try {
      onFocusSettingsChanged(await client.setFocusSetting(key, value));
      return true;
    } catch (err) {
      setSettingMessage(err instanceof Error ? err.message : String(err));
      return false;
    } finally {
      setSettingBusy(false);
    }
  };
  const [stackRunning, setStackRunning] = useState(false);
  const [startPos, setStartPos] = useState<string>('');
  const [endPos, setEndPos] = useState<string>('');
  const [stepInput, setStepInput] = useState<string>('4');
  const [settleInput, setSettleInput] = useState<string>('150');
  const [stackStatus, setStackStatus] = useState<string | null>(null);
  const [targetFocus, setTargetFocus] = useState<number | null>(null);
  const targetFocusRef = useRef<number | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const gotoAbortRef = useRef<AbortController | null>(null);
  const focusCommitTimerRef = useRef<number | null>(null);
  const prefixClearTimerRef = useRef<number | null>(null);
  useEffect(() => () => {
    abortRef.current?.abort();
    gotoAbortRef.current?.abort();
    if (focusCommitTimerRef.current !== null) window.clearTimeout(focusCommitTimerRef.current);
    onStackModeChange(false);
  }, [onStackModeChange]);

  const parsedA = startPos !== '' ? Number(startPos) : NaN;
  const parsedB = endPos !== '' ? Number(endPos) : NaN;
  const parsedStep = stepInput.trim() !== '' ? Number(stepInput) : NaN;
  const isStepValid = Number.isInteger(parsedStep) && parsedStep >= 1;
  const currentFocus = state?.focus?.currentPosition;
  const maxFocus = state?.focus?.maxPosition;
  const lensRangeKnown = typeof maxFocus === 'number' && maxFocus > 0;
  const isManualFocus = focusSettings?.mode === 'manual';
  const isFlexibleSpot = !isManualFocus && focusSettings?.area === 'flex-spot';
  const canSwitchToMF = focusSettings?.modes.includes('manual') === true;
  const enterStackMode = async () => {
    setStackStatus(null);
    priorFocusMode.current = isManualFocus ? undefined : focusSettings?.mode;
    onStackModeChange(true);
    if (!isManualFocus && !(await changeFocusSetting('mode', 'manual'))) {
      priorFocusMode.current = undefined;
      onStackModeChange(false);
    }
  };
  const exitStackMode = async () => {
    const previous = priorFocusMode.current;
    priorFocusMode.current = undefined;
    if (previous && isManualFocus && connected) await changeFocusSetting('mode', previous);
    onStackModeChange(false);
  };
  const endpointsValid = lensRangeKnown && Number.isInteger(parsedA) && Number.isInteger(parsedB)
    && parsedA >= 0 && parsedB >= 0 && parsedA <= maxFocus && parsedB <= maxFocus && parsedA !== parsedB;
  const plannedShots = endpointsValid && isStepValid
    ? computeFocusStackPositions(parsedA, parsedB, parsedStep).length : null;
  const canDragFocus =
    connected && !stackRunning && !settingBusy && isManualFocus && lensRangeKnown;
  const canMarkPosition = canDragFocus && targetFocus === null && typeof currentFocus === 'number';
  const canStartStack = stackMode && canDragFocus && !busy && endpointsValid && isStepValid;
  const displayFocus =
    targetFocus ?? (typeof currentFocus === 'number' ? currentFocus : 0);
  const pct =
    typeof maxFocus === 'number' && maxFocus > 0
      ? Math.min(100, Math.max(0, (displayFocus / maxFocus) * 100))
      : 0;

  const moveFocusTo = async (rawTarget: number) => {
    if (abortRef.current !== null) return;
    if (!canDragFocus || typeof maxFocus !== 'number') {
      targetFocusRef.current = null;
      setTargetFocus(null);
      return;
    }
    const target = Math.max(0, Math.min(maxFocus, Math.round(rawTarget)));
    targetFocusRef.current = target;
    setTargetFocus(target);

    gotoAbortRef.current?.abort();
    const controller = new AbortController();
    gotoAbortRef.current = controller;
    const timeout = window.setTimeout(() => {
      controller.abort();
      if (gotoAbortRef.current === controller) onError('调焦等待超时，请根据相机回报的位置继续');
    }, 9000);
    try {
      await client.gotoFocus(target, 0, controller.signal);
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        return;
      }
      onError(err instanceof Error ? err.message : String(err));
    } finally {
      window.clearTimeout(timeout);
      if (gotoAbortRef.current === controller) {
        gotoAbortRef.current = null;
        targetFocusRef.current = null;
        setTargetFocus(null);
      }
    }
  };

  const clearFocusCommit = () => {
    if (focusCommitTimerRef.current !== null) window.clearTimeout(focusCommitTimerRef.current);
    focusCommitTimerRef.current = null;
  };

  const queueFocusCommit = (target: number) => {
    if (abortRef.current !== null || !Number.isFinite(target)) return;
    clearFocusCommit();
    // Android WebView may dispatch the final input after pointerup, or omit pointerup on a scroll.
    targetFocusRef.current = target;
    setTargetFocus(target);
    focusCommitTimerRef.current = window.setTimeout(() => {
      focusCommitTimerRef.current = null;
      void moveFocusTo(target);
    }, 160);
  };

  const stepFocus = (delta: number) => {
    if (typeof currentFocus !== 'number' || !canDragFocus) {
      return;
    }
    const base = targetFocusRef.current ?? currentFocus;
    clearFocusCommit();
    void moveFocusTo(base + delta);
  };

  const handleStartStack = async () => {
    if (abortRef.current !== null) return;
    const a = Number(startPos);
    const b = Number(endPos);
    if (!stackMode || !isManualFocus || !endpointsValid) {
      onError('请在 MF 模式下设置两个不同且在镜头范围内的焦点位置');
      return;
    }
    const stepVal = stepInput.trim() !== '' ? Number(stepInput) : NaN;
    if (!Number.isInteger(stepVal) || stepVal < 1) {
      onError('焦点步长须为大于等于 1 的整数');
      return;
    }
    const settleVal = settleInput.trim() !== '' ? Number(settleInput) : NaN;
    if (!Number.isFinite(settleVal) || settleVal < 0 || settleVal > 5000) {
      onError('稳定等待时间须在 0 到 5000 毫秒之间');
      return;
    }
    clearFocusCommit();
    // The first bracket position supersedes any preview move still waiting for the camera.
    gotoAbortRef.current?.abort();
    gotoAbortRef.current = null;
    targetFocusRef.current = null;
    setTargetFocus(null);
    if (prefixClearTimerRef.current !== null) {
      window.clearTimeout(prefixClearTimerRef.current);
      prefixClearTimerRef.current = null;
    }
    const runBaseName = formatCaptureBaseName();
    const useAutoMerge = openFocusAvailable && autoMerge;
    const expectedShots = computeFocusStackPositions(a, b, stepVal).length;
    onStackSessionChange?.(runBaseName);

    const controller = new AbortController();
    abortRef.current = controller;
    setBusy(true);
    setStackRunning(true);
    setStackStatus('正在开始对焦包围…');
    let aborted = false;
    try {
      await onStackSessionStart?.(runBaseName, useAutoMerge);
      await client.runFocusStack(
        a,
        b,
        stepVal,
        settleVal,
        ({ index, total }) => {
          setTargetFocus(null);
          setStackStatus(`正在拍摄第 ${index}/${total} 张`);
        },
        controller.signal,
        (targetPos) => {
          setTargetFocus(targetPos);
        },
        onCaptureStackShot,
      );
      setStackStatus('对焦包围已完成');
    } catch (err) {
      aborted = true;
      if (err instanceof DOMException && err.name === 'AbortError') {
        setStackStatus('对焦包围已停止');
      } else {
        const msg = err instanceof Error ? err.message : String(err);
        setStackStatus(msg);
        onError(msg);
      }
    } finally {
      setTargetFocus(null);
      abortRef.current = null;
      setStackRunning(false);
      setBusy(false);
      void onStackSessionEnd?.(runBaseName, useAutoMerge, aborted, expectedShots);
      prefixClearTimerRef.current = window.setTimeout(() => {
        onStackSessionChange?.(null);
        prefixClearTimerRef.current = null;
      }, 3500);
    }
  };

  const handleAbortStack = () => {
    abortRef.current?.abort();
  };

  const focusButtons = [
    { delta: -20, symbol: '««', label: '向近处对焦（-20)' },
    { delta: -5, symbol: '«', label: '向近处对焦（-5)' },
    { delta: -1, symbol: '‹', label: '向近处对焦（-1)' },
    { delta: 1, symbol: '›', label: '向远处对焦（+1)' },
    { delta: 5, symbol: '»', label: '向远处对焦（+5)' },
    { delta: 20, symbol: '»»', label: '向远处对焦（+20)' },
  ];

  return (
    <section className="panel">
      <div className="panel-header focus-header">
        <div className="panel-title">
          <span>对焦</span>
          <span className="badge focus-pos-badge" title="镜头报告的焦点位置索引">
            位置{' '}
            {targetFocus !== null && targetFocus !== currentFocus
              ? `${currentFocus ?? '--'} → ${targetFocus}`
              : (currentFocus ?? '--')}{' '}
            / {maxFocus ?? '--'}
          </span>
        </div>
        <div className="focus-buttons-inline">
          {focusButtons.map(({ delta, symbol, label }) => (
            <button
              key={delta}
              type="button"
              className="btn btn-sm"
              disabled={!canDragFocus || typeof currentFocus !== 'number'}
              onClick={() => stepFocus(delta)}
              title={label}
              aria-label={label}
            >
              {symbol}
            </button>
          ))}
        </div>
      </div>

      {!stackMode && (
        <div className="regular-focus-settings">
          {([
            ['mode', '对焦模式', focusSettings?.modes, FOCUS_MODE_LABELS],
            ['area', '对焦区域', focusSettings?.areas, FOCUS_AREA_LABELS],
            ['spotSize', '自由点大小', focusSettings?.spotSizes, FOCUS_SPOT_SIZE_LABELS],
          ] as const).map(([key, label, supported, labels]) => {
            if (key === 'spotSize' && !isFlexibleSpot) return null;
            const value = focusSettings?.[key] ?? '';
            const choices = [...new Set(supported ?? [])];
            return (
              <label key={key}>
                {label}{' '}
                <select
                  className="select-input"
                  aria-label={label}
                  value={value}
                  disabled={!connected || busy || settingBusy || choices.length === 0
                    || (key === 'area' && isManualFocus)}
                  onChange={(e) => void changeFocusSetting(key, e.target.value)}
                >
                  {!choices.includes(value) && <option value={value} disabled>{labels[value] ?? (value || '不可用')}</option>}
                  {choices.map((option) => <option key={option} value={option}>{labels[option] ?? option}</option>)}
                </select>
              </label>
            );
          })}
          {!focusSettings && (
            <button type="button" className="btn btn-sm" disabled={!connected || settingBusy}
              onClick={() => void readFocusSettings()}>读取相机设置</button>
          )}
          {isManualFocus && <p className="muted-tag">切换到自动对焦后可设置对焦区域。</p>}
        </div>
      )}
      {settingMessage && <p role="status" className="muted-tag">{settingMessage}</p>}
      <div
        className="focus-slider-wrap"
        title={`镜头焦点位置 ${displayFocus} / ${maxFocus ?? '未知'}`}
      >
        <div className="progress-track">
          <div
            className="progress-fill"
            style={{
              width: `${pct}%`,
              transition: targetFocus !== null ? 'none' : undefined,
            }}
          />
        </div>
        <input
          type="range"
          className="focus-slider"
          min={0}
          max={typeof maxFocus === 'number' && maxFocus > 0 ? maxFocus : 100}
          value={displayFocus}
          disabled={!canDragFocus}
          aria-label="手动对焦位置"
          onChange={(e) => queueFocusCommit(e.currentTarget.valueAsNumber)}
        />
      </div>

      <div className="subpanel">
        <div className="subpanel-title">对焦包围拍摄 <span className="badge">固定 MF</span></div>
        <div className="toolbar-row">
          <span className="muted-tag">
            {!stackMode ? '启用后使用手动对焦。'
              : !isManualFocus ? '等待相机确认 MF。'
              : !lensRangeKnown ? '尚未取得镜头位置，可先转动镜头对焦环。'
              : '先把镜头移至想要的两个焦点，再分别记录 A、B。'}
          </span>
          {stackMode ? (
            <button type="button" className="btn btn-sm" disabled={settingBusy || stackRunning}
              onClick={() => void exitStackMode()}>退出包围设置</button>
          ) : (
            <button type="button" className="btn btn-sm" disabled={!connected || settingBusy || !(isManualFocus || canSwitchToMF)}
              onClick={() => void enterStackMode()}>启用对焦包围</button>
          )}
        </div>
        {stackMode && <>
        <div className="stack-grid">
          <div className="field-inline stack-col-left">
            <label>起点 A</label>
            <input
              type="number"
              className="input-sm"
              value={startPos}
              onChange={(e) => setStartPos(e.target.value)}
              placeholder="位置"
              aria-label="起点 A 的焦点位置"
              title="起点 A 的焦点位置"
            />
            <button
              type="button"
              className="btn btn-sm"
              disabled={!canMarkPosition}
              onClick={() => setStartPos(String(currentFocus ?? ''))}
              title="用当前焦点位置设置起点 A"
              aria-label="用当前焦点位置设置起点 A"
            >
              设置
            </button>
          </div>

          <button
            type="button"
            className="btn btn-sm stack-swap-btn"
            disabled={stackRunning || (startPos === '' && endPos === '')}
            onClick={() => {
              setStartPos(endPos);
              setEndPos(startPos);
            }}
            title="交换起点 A 和终点 B"
            aria-label="交换起点 A 和终点 B"
          >
            ⇄
          </button>

          <div className="field-inline stack-col-right">
            <label>终点 B</label>
            <input
              type="number"
              className="input-sm"
              value={endPos}
              onChange={(e) => setEndPos(e.target.value)}
              placeholder="位置"
              aria-label="终点 B 的焦点位置"
              title="终点 B 的焦点位置"
            />
            <button
              type="button"
              className="btn btn-sm"
              disabled={!canMarkPosition}
              onClick={() => setEndPos(String(currentFocus ?? ''))}
              title="用当前焦点位置设置终点 B"
              aria-label="用当前焦点位置设置终点 B"
            >
              设置
            </button>
          </div>

          <div className="field-inline stack-col-left">
            <label>焦点步长</label>
            <input
              type="number"
              min={1}
              max={500}
              className="input-sm"
              value={stepInput}
              placeholder="4"
              onChange={(e) => setStepInput(e.target.value)}
              aria-label="焦点步长"
              title="相邻照片之间的焦点步长"
            />
            <span className="muted-tag">
              {plannedShots !== null ? `${plannedShots} 张` : '-- 张'}
            </span>
          </div>

          <div className="field-inline stack-col-right">
            <label>稳定等待（毫秒）</label>
            <input
              type="number"
              min={0}
              max={5000}
              step={50}
              className="input-sm"
              value={settleInput}
              placeholder="150"
              onChange={(e) => setSettleInput(e.target.value)}
              aria-label="稳定等待时间（毫秒）"
              title="焦点移动完成后到拍摄前的等待时间"
            />
          </div>
        </div>

        <div className="stack-actions" style={{ flexWrap: 'wrap' }}>
          {!hideAutoMerge && (
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6, marginRight: 'auto' }}>
              <label className={`checkbox-row ${!openFocusAvailable ? 'is-disabled' : ''}`}>
                <input
                  type="checkbox"
                  disabled={!openFocusAvailable || stackRunning}
                  checked={openFocusAvailable && autoMerge}
                  onChange={(e) => onAutoMergeChange(e.target.checked)}
                />
                <span>自动合成</span>
              </label>
              {!openFocusAvailable && (
                <span className="info-tooltip-wrap">
                  <span className="info-icon" tabIndex={0} aria-label={openFocusReason}>
                    i
                  </span>
                  <span className="info-tooltip-bubble">{openFocusReason}</span>
                </span>
              )}
            </div>
          )}
          {(mergeStatus || stackStatus) && (
            <span className="stack-feedback muted-tag" role="status">
              {mergeStatus ?? stackStatus}
            </span>
          )}
          {!stackRunning ? (
            <button
              type="button"
              className="btn btn-primary btn-sm"
              disabled={!canStartStack}
              onClick={handleStartStack}
            >
              开始包围拍摄
            </button>
          ) : (
            <button
              type="button"
              className="btn btn-danger btn-sm"
              onClick={handleAbortStack}
            >
              停止包围拍摄
            </button>
          )}
        </div>
        </>}
      </div>
    </section>
  );
}
