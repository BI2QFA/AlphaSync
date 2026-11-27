import { useEffect, useMemo, useState } from 'react';
import type { CameraClient, DescribeMethod } from '../api/cameraClient';

interface RpcInspectorProps {
  client: CameraClient;
  connected: boolean;
}

export function RpcInspector({ client, connected }: RpcInspectorProps) {
  const [targets, setTargets] = useState<Record<string, DescribeMethod[]>>({});
  const [target, setTarget] = useState<string>('camera');
  const [method, setMethod] = useState<string>('getSupportedISOSensitivities');
  const [argsText, setArgsText] = useState<string>('[]');
  const [search, setSearch] = useState<string>('');
  const [output, setOutput] = useState<string>('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!connected) return;
    client
      .describe()
      .then((res) => {
        setTargets(res.targets ?? {});
      })
      .catch(() => {
        // ignore if describe fails transiently
      });
  }, [client, connected]);

  const targetNames = useMemo(() => Object.keys(targets), [targets]);

  const filteredMethods = useMemo(() => {
    const list = targets[target] ?? [];
    const q = search.trim().toLowerCase();
    if (!q) return list.slice(0, 60);
    return list.filter((m) => m.name.toLowerCase().includes(q)).slice(0, 60);
  }, [targets, target, search]);

  const handleInvoke = async () => {
    setBusy(true);
    try {
      const parsedArgs = argsText.trim() ? (JSON.parse(argsText) as unknown[]) : [];
      if (!Array.isArray(parsedArgs)) {
        throw new Error('Args must be a JSON array, e.g. [100] or []');
      }
      const res = await client.call(target, method, parsedArgs);
      setOutput(JSON.stringify(res, null, 2));
    } catch (err) {
      setOutput(`ERROR: ${err instanceof Error ? err.message : String(err)}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="panel">
      <div className="panel-header">
        <div className="panel-title">
          <span>RPC 调试（/api/call 与 /api/describe）</span>
        </div>
      </div>

      <div className="rpc-form">
        <select
          className="select-input"
          value={target}
          onChange={(e) => setTarget(e.target.value)}
          aria-label="RPC target"
          title="Select RPC target object"
        >
          {(targetNames.length > 0
            ? targetNames
            : ['camera', 'modifier', 'params', 'static:System', 'static:Temperature', 'static:ScalarProperties']
          ).map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>

        <input
          type="text"
          className="input-text"
          value={method}
          onChange={(e) => setMethod(e.target.value)}
          placeholder="方法名称"
          aria-label="RPC method name"
        />

        <input
          type="text"
          className="input-text"
          value={argsText}
          onChange={(e) => setArgsText(e.target.value)}
          placeholder="JSON 参数数组，例如 []"
          aria-label="RPC arguments JSON array"
        />

        <button
          type="button"
          className="btn btn-primary"
          disabled={!connected || busy || !method.trim()}
          onClick={handleInvoke}
          title="调用 RPC 方法"
        >
          {busy ? 'Calling...' : 'Call'}
        </button>
      </div>

      {targetNames.length > 0 && (
        <div className="describe-box">
          <input
            type="text"
            className="input-text input-search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={`Filter ${target} methods...`}
            aria-label="Filter RPC methods"
          />
          <div className="method-chips">
            {filteredMethods.map((m) => (
              <button
                key={`${m.name}-${m.params.join(',')}`}
                type="button"
                className="chip"
                onClick={() => {
                  setMethod(m.name);
                  setArgsText('[]');
                }}
                title={`${m.returnType} ${m.name}(${m.params.join(', ')})`}
              >
                {m.name}({m.params.length})
              </button>
            ))}
          </div>
        </div>
      )}

      {output && <pre className="rpc-output">{output}</pre>}
    </section>
  );
}
