import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  ArrowLeftRight, ArrowRight, Check, ChevronRight, Clock3, Cloud,
  Cpu, Gamepad2, Layers2, Moon, Network, Package, RotateCcw, Settings2,
  Signal, Sun, Trophy, Wifi, X, BatteryFull, Monitor, Store,
} from 'lucide-react';
import launcherIcon from '../../../app/src/main/res/mipmap-xxxhdpi/ic_launcher_amethyst_round.png';

const KEY = 'stamethyst-renderer-prototype-v1';
const BACKENDS = {
  mobileglues: {
    name: 'MobileGlues', caption: '兼容转译', icon: Layers2,
    summary: '更偏兼容性，部分设备仍需调校。',
    detail: '使用 MobileGlues 转译图形指令，更偏兼容性。部分设备可能需要进一步调整参数。',
  },
  gles2: {
    name: 'GLES2', caption: '原生渲染', icon: Monitor,
    summary: '沿用原生渲染路径，功能集更保守。',
    detail: '使用原生 OpenGL ES 2，最接近旧版实现，功能集更保守。',
  },
};

function readSaved() {
  try { return JSON.parse(localStorage.getItem(KEY)) || {}; } catch { return {}; }
}

function IconTile({ icon: Icon, tone = 'primary' }) {
  return <span className={`icon-tile ${tone}`}><Icon size={24} strokeWidth={1.8} aria-hidden="true" /></span>;
}

function Modal({ title, icon: Icon = ArrowLeftRight, children, actions, onClose }) {
  const ref = useRef(null);
  useEffect(() => {
    const dialog = ref.current;
    const trigger = document.activeElement;
    dialog.showModal();
    return () => {
      dialog.close();
      const target = trigger?.isConnected ? trigger : document.querySelector('.renderer-selector');
      target?.focus();
    };
  }, []);
  function trapFocus(event) {
    if (event.key !== 'Tab') return;
    const controls = [...ref.current.querySelectorAll('button:not(:disabled), input:not(:disabled)')];
    const first = controls[0];
    const last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault(); last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault(); first?.focus();
    }
  }
  return <dialog ref={ref} className="dialog" aria-labelledby="dialog-title"
    onKeyDown={trapFocus}
    onCancel={e => { e.preventDefault(); onClose(); }}
    onClick={e => { if (e.target === ref.current) onClose(); }}>
    <div className="dialog-content">
      <div className="dialog-top"><IconTile icon={Icon} /><button className="icon-button" aria-label="关闭弹窗" title="关闭弹窗" onClick={onClose}><X size={21} /></button></div>
      <h2 id="dialog-title">{title}</h2>
      <div className="dialog-body">{children}</div>
      <div className="dialog-actions">{actions || <button className="primary-button" onClick={onClose}>完成</button>}</div>
    </div>
  </dialog>;
}

function RendererCard({ renderer, mode, changed, onSelect, onAuto }) {
  const selected = BACKENDS[renderer];
  return <section className="card renderer-card" aria-labelledby="renderer-title">
    <div className="card-heading">
      <IconTile icon={Cpu} />
      <div className="heading-text"><h2 id="renderer-title">图形渲染</h2><p>游戏使用的图形后端</p></div>
      <span className="mode-label">{mode === 'auto' ? '自动' : '手动'}</span>
    </div>
    <button className="renderer-selector" aria-label={`当前 ${selected.name}，切换到 ${BACKENDS[renderer === 'mobileglues' ? 'gles2' : 'mobileglues'].name}`}
      aria-haspopup="dialog" data-selection={renderer} onClick={() => onSelect(renderer === 'mobileglues' ? 'gles2' : 'mobileglues')}>
      {Object.entries(BACKENDS).map(([id, item]) => {
        const BackgroundIcon = id === 'mobileglues' ? Layers2 : Cpu;
        return <span key={id} className={`renderer-option renderer-${id} ${id === renderer ? 'selected' : ''}`} aria-hidden="true">
          <BackgroundIcon className="renderer-background-icon" size={132} strokeWidth={1} aria-hidden="true" />
          <span className="option-status" aria-hidden="true">{id === renderer ? <><Check size={14} strokeWidth={2.5} /><span>已选中</span></> : <ArrowLeftRight size={17} />}</span>
          <span className="option-label">{id === 'mobileglues' ? <span>Mobile<wbr /><span className="wordmark-accent">Glues</span></span> : <span>GLES<span className="version-mark">2</span></span>}</span>
          <span className="option-caption">{item.caption}</span>
        </span>;
      })}
      <span className="diagonal-divider" aria-hidden="true"></span>
    </button>
    <p className="renderer-summary" key={renderer}>{selected.summary}</p>
    <div className="renderer-footer">
      <span className="effective-note"><Clock3 size={14} aria-hidden="true" />{changed ? '已保存，下次启动生效' : '下次启动使用此后端'}</span>
      {mode === 'manual' && <button className="icon-button restore-auto" aria-label="恢复自动选择" title="恢复自动选择" onClick={onAuto}><RotateCcw size={17} /></button>}
    </div>
  </section>;
}

function OverviewCard({ mods, onOpen }) {
  return <section className="card overview-card" aria-labelledby="overview-title">
    <h2 id="overview-title">游戏概览</h2>
    <div className="metrics">
      <button className="metric" onClick={onOpen}><span>已启用模组</span><strong>{mods.filter(m => m.enabled).length}<small> / {mods.length}</small></strong></button>
      <button className="metric" onClick={onOpen}><span>模组大小</span><strong>{mods.filter(m => m.enabled).reduce((size, m) => size + m.size, 0).toFixed(1)}<small> MB</small></strong></button>
    </div>
  </section>;
}

function ContextCard({ icon, tone, title, subtitle, detail, onClick }) {
  return <button className="card context-card" onClick={onClick}>
    <IconTile icon={icon} tone={tone} />
    <span className="context-copy"><strong>{title}</strong><span>{subtitle}</span>{detail && <small>{detail}</small>}</span>
    <ChevronRight className="context-chevron" size={18} aria-hidden="true" />
  </button>;
}

function App() {
  const [saved] = useState(readSaved);
  const [theme, setTheme] = useState(saved.theme === 'dark' ? 'dark' : 'light');
  const [renderer, setRenderer] = useState(saved.renderer === 'gles2' ? 'gles2' : 'mobileglues');
  const [mode, setMode] = useState(saved.mode === 'manual' ? 'manual' : 'auto');
  const [changed, setChanged] = useState(saved.mode === 'manual');
  const [modal, setModal] = useState(null);
  const [toast, setToast] = useState('');
  const [mods, setMods] = useState([
    { name: 'BaseMod', size: 13.2, enabled: true },
    { name: 'StSLib', size: 2.6, enabled: true },
  ]);
  const [checking, setChecking] = useState(false);
  const [cloudChecked, setCloudChecked] = useState(false);
  const cloudTimer = useRef(null);
  useEffect(() => {
    document.documentElement.dataset.themeMode = theme;
    try { localStorage.setItem(KEY, JSON.stringify({ theme, renderer, mode })); } catch { /* File previews may block storage. */ }
  }, [theme, renderer, mode]);
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(''), 3500);
    return () => clearTimeout(timer);
  }, [toast]);
  useEffect(() => () => clearTimeout(cloudTimer.current), []);
  const close = () => setModal(null);
  function select(id) {
    if (id === renderer && mode === 'manual') return;
    setModal({ type: 'switch', target: id });
  }
  function confirmSwitch() {
    const next = modal.target;
    setRenderer(next); setMode('manual'); setChanged(true); close();
    setToast(`已选择 ${BACKENDS[next].name}`);
  }
  function restoreAuto() {
    setRenderer('mobileglues'); setMode('auto'); setChanged(true); close();
    setToast('已恢复自动选择');
  }
  function checkCloud() {
    setChecking(true);
    cloudTimer.current = setTimeout(() => { setChecking(false); setCloudChecked(true); }, 900);
  }
  const open = type => setModal({ type });
  return <>
    <div className="preview-toolbar">
      <div className="preview-title"><span>游戏页</span><span className="toolbar-divider"></span><strong>图形渲染</strong></div>
      <div className="theme-control" role="group" aria-label="预览主题">
        <button aria-label="浅色主题" title="浅色主题" aria-pressed={theme === 'light'} onClick={() => setTheme('light')}><Sun size={18} /></button>
        <button aria-label="深色主题" title="深色主题" aria-pressed={theme === 'dark'} onClick={() => setTheme('dark')}><Moon size={18} /></button>
      </div>
    </div>
    <div className="device">
      <div className="status-bar" aria-hidden="true"><span>9:41</span><span><Signal size={14} /><Wifi size={15} /><BatteryFull size={20} /></span></div>
      <header className="app-header">
        <img src={launcherIcon} width="48" height="48" alt="" />
        <div><h1>杀戮尖塔模组启动器</h1><p>v1.6.1</p></div>
      </header>
      <main className="game-content" aria-label="游戏">
        <OverviewCard mods={mods} onOpen={() => open('mods')} />
        <RendererCard renderer={renderer} mode={mode} changed={changed} onSelect={select} onAuto={() => open('auto')} />
        <ContextCard icon={Cloud} tone="green" title="云存档状态" subtitle="Steam Cloud 已是最新" onClick={() => open('cloud')} />
        <ContextCard icon={Network} tone="secondary" title="虚拟局域网" subtitle="未连接" onClick={() => open('network')} />
        <ContextCard icon={Trophy} tone="tertiary" title="成就" subtitle="查看与同步 Steam 成就" onClick={() => open('achievements')} />
      </main>
      <div className="launch-bar"><button className="launch-button" onClick={() => open('launch')}><Gamepad2 size={21} />启动游戏</button></div>
      <nav className="bottom-nav" aria-label="主导航">
        {[[Gamepad2, '游戏', null], [Package, '模组', 'mods'], [Store, '创意工坊', 'workshop'], [Settings2, '设置', 'settings']].map(([Icon, title, target]) =>
          <button key={title} className={target === null ? 'active' : ''} aria-current={target === null ? 'page' : undefined}
            onClick={() => target ? open(target) : document.querySelector('.game-content').scrollTo({ top: 0, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' })}>
            <span className="nav-icon"><Icon size={22} /></span><span>{title}</span>
          </button>)}
      </nav>
      <div className="gesture-bar" aria-hidden="true"><span></span></div>
      <div className="toast-region" role="status" aria-live="polite">{toast && <div className="toast" key={toast}><Check size={17} />{toast}</div>}</div>
    </div>

    {modal?.type === 'switch' && <Modal title={`切换到 ${BACKENDS[modal.target].name}？`} onClose={close}
      actions={<><button className="text-button" onClick={close}>取消</button><button className="primary-button" onClick={confirmSwitch}>确认切换<ArrowRight size={17} /></button></>}>
      <div className="backend-comparison">
        <div><span>当前{mode === 'auto' ? ' · 自动' : ''}</span><strong>{BACKENDS[renderer].name}</strong></div>
        <ArrowRight size={20} aria-hidden="true" />
        <div className="next-backend"><span>切换后 · 手动</span><strong>{BACKENDS[modal.target].name}</strong></div>
      </div>
      <p>{BACKENDS[modal.target].detail}</p>
      {mode === 'auto' && <p>确认后将关闭自动选择，使用你指定的图形后端。</p>}
      <div className="timing-note"><Clock3 size={17} /><span>下次启动游戏时生效</span></div>
    </Modal>}
    {modal?.type === 'auto' && <Modal title="恢复自动选择？" icon={RotateCcw} onClose={close}
      actions={<><button className="text-button" onClick={close}>取消</button><button className="primary-button" onClick={restoreAuto}>恢复自动</button></>}>
      <p>根据设备支持情况选择可用的图形后端。当前自动选择为 MobileGlues。</p><div className="timing-note"><Clock3 size={17} />下次启动游戏时生效</div>
    </Modal>}
    {modal?.type === 'mods' && <Modal title="模组" icon={Package} onClose={close}>
      <div className="simple-list">{mods.map((mod, index) => <label className="setting-row" key={mod.name}><span><strong>{mod.name}</strong><small>{mod.size.toFixed(1)} MB</small></span>
        <input type="checkbox" checked={mod.enabled} onChange={() => setMods(items => items.map((item, i) => i === index ? { ...item, enabled: !item.enabled } : item))} /></label>)}</div>
    </Modal>}
    {modal?.type === 'cloud' && <Modal title="云存档状态" icon={Cloud} onClose={close}
      actions={<><button className="text-button" onClick={close}>关闭</button><button className="primary-button" disabled={checking} onClick={checkCloud}>{checking ? '正在检查…' : '重新检查'}</button></>}>
      <p role="status">{checking ? '正在检查云端变更。' : 'Steam Cloud 已是最新。'}</p><p>{cloudChecked ? '刚刚完成检查' : '本地与云端存档一致。'}</p>
    </Modal>}
    {modal?.type === 'network' && <Modal title="虚拟局域网" icon={Network} onClose={close}><p>未连接</p><p>暂无已加入的房间。</p></Modal>}
    {modal?.type === 'achievements' && <Modal title="成就" icon={Trophy} onClose={close}><p>请先登录 Steam 以查看成就。</p></Modal>}
    {modal?.type === 'workshop' && <Modal title="创意工坊" icon={Store} onClose={close}><p>暂无下载任务</p></Modal>}
    {modal?.type === 'settings' && <Modal title="外观" icon={Settings2} onClose={close}><label className="setting-row"><span>深色模式</span><input type="checkbox" checked={theme === 'dark'} onChange={e => setTheme(e.target.checked ? 'dark' : 'light')} /></label></Modal>}
    {modal?.type === 'launch' && <Modal title="启动配置" icon={Gamepad2} onClose={close}>
      <div className="simple-list"><div className="setting-row"><span>图形后端</span><strong>{BACKENDS[renderer].name}</strong></div><div className="setting-row"><span>选择模式</span><strong>{mode === 'auto' ? '自动' : '手动'}</strong></div><div className="setting-row"><span>已启用模组</span><strong>{mods.filter(m => m.enabled).length}</strong></div></div>
    </Modal>}
  </>;
}

createRoot(document.getElementById('root')).render(<App />);
