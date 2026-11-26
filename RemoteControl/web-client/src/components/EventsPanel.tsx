import { useMemo, useState } from 'react';
import { type CameraEvent } from '../api/cameraClient';

export interface LoggedCameraEvent extends CameraEvent {
  receivedAt: number;
}

interface EventsPanelProps {
  events: LoggedCameraEvent[];
  onClear: () => void;
}

function formatWallTime(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number, w = 2) => String(n).padStart(w, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

function formatCameraTick(t: number): string {
  if (!Number.isFinite(t)) return '--';
  return `${(t / 1000).toFixed(2)}s`;
}

export function EventsPanel({ events, onClear }: EventsPanelProps) {
  const [typeFilter, setTypeFilter] = useState<string>('ALL');
  const [expandedKeys, setExpandedKeys] = useState<Set<string>>(() => new Set());

  const toggleExpand = (key: string) => {
    setExpandedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  const eventTypes = useMemo(() => {
    const set = new Set<string>();
    for (const ev of events) {
      set.add(ev.type);
    }
    return Array.from(set).sort();
  }, [events]);

  const filteredEvents = useMemo(() => {
    if (typeFilter === 'ALL') return events;
    return events.filter((ev) => ev.type === typeFilter);
  }, [events, typeFilter]);

  return (
    <section className="panel">
      <div className="panel-header">
        <div className="panel-title">
          <span>相机事件</span>
          <span className="badge">{filteredEvents.length} / {events.length}</span>
        </div>
        <div className="toolbar-row">
          <select
            className="select-input btn-sm"
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value)}
            aria-label="Filter events by type"
          >
            <option value="ALL">全部类型</option>
            {eventTypes.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="btn btn-sm"
            disabled={events.length === 0}
            onClick={() => {
              setExpandedKeys(new Set());
              onClear();
            }}
          >
            清空
          </button>
        </div>
      </div>

      {filteredEvents.length === 0 ? (
        <div className="empty-state">暂无相机事件</div>
      ) : (
        <div className="event-log event-log-detailed">
          {filteredEvents.map((ev) => {
            const key = `${ev.seq}-${ev.receivedAt}`;
            const hasData = ev.data !== undefined && Object.keys(ev.data).length > 0;
            const isExpanded = hasData && expandedKeys.has(key);
            return (
              <div key={key} className="event-detail-card">
                <div
                  className={`event-row ${hasData ? 'event-row-clickable' : ''}`}
                  onClick={hasData ? () => toggleExpand(key) : undefined}
                  role={hasData ? 'button' : undefined}
                  tabIndex={hasData ? 0 : undefined}
                  aria-expanded={hasData ? isExpanded : undefined}
                  onKeyDown={
                    hasData
                      ? (e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            toggleExpand(key);
                          }
                        }
                      : undefined
                  }
                >
                  <span className="event-chevron">
                    {hasData ? (isExpanded ? '▾' : '▸') : '·'}
                  </span>
                  <span className="badge">#{ev.seq}</span>
                  <span className="event-type">{ev.type}</span>
                  <span className="muted-tag" style={{ marginLeft: 'auto' }}>
                    {formatWallTime(ev.receivedAt)} · cam +{formatCameraTick(ev.t)}
                  </span>
                </div>
                {isExpanded && (
                  <pre className="event-data-json">
                    {JSON.stringify(ev.data, null, 2)}
                  </pre>
                )}
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}
