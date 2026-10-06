const ICONS = {
  grid: <><rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/></>,
  spark: <path d="m12 3 2.6 6.4L21 12l-6.4 2.6L12 21l-2.6-6.4L3 12l6.4-2.6L12 3Z"/>,
  file: <><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><path d="M14 2v6h6M8 13h8M8 17h5"/></>,
  user: <><circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/></>,
  bookmark: <path d="M6 3h12v18l-6-4-6 4V3Z"/>,
  arrow: <path d="M4 12h16m-6-6 6 6-6 6"/>,
  chevron: <path d="m9 5 7 7-7 7"/>,
  check: <path d="m5 12 4 4L19 6"/>,
  plus: <path d="M12 5v14M5 12h14"/>,
  close: <path d="m6 6 12 12M18 6 6 18"/>,
  pin: <><path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2"/></>,
  search: <><circle cx="10" cy="10" r="7"/><path d="m15 15 6 6"/></>,
  briefcase: <><rect x="3" y="7" width="18" height="14" rx="2"/><path d="M8 7V3h8v4M3 12a24 24 0 0 0 18 0M12 12v4"/></>,
  logout: <path d="M9 4H4v16h5M9 12h12m-5-5 5 5-5 5"/>,
  leaf: <path d="M20 3C7 1 1 10 7 16s15 0 13-13ZM5 20 15 10"/>,
};

export function Icon({ name, className = '' }) {
  return (
    <svg className={'icon ' + className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {ICONS[name] || ICONS.spark}
    </svg>
  );
}

export function Logo() {
  return (
    <a className="brand" href="#/" aria-label="온핏 홈">
      <span className="brand-mark">o<span></span></span>
      <span>온핏<span className="brand-en">onfit</span></span>
    </a>
  );
}

export function Pill({ children, className = '', as: Tag = 'span' }) {
  return <Tag className={'pill ' + className}>{children}</Tag>;
}

/** items: [text, className?] pairs rendered as list items. */
export function PillList({ className, items }) {
  return <ul className={className}>{items.map(([text, cls = ''], i) => <Pill key={text + i} as="li" className={cls}>{text}</Pill>)}</ul>;
}

export function SkillResult({ skills, className, emptyText }) {
  return skills.length
    ? <PillList className="job-skills" items={skills.map(skill => [skill, className])} />
    : <div className="job-skills"><p className="subtle">{emptyText}</p></div>;
}

export function Plant() {
  return (
    <div className="plant-art" aria-hidden="true">
      <div className="sun"></div><div className="orbit-line"></div><div className="stem"></div>
      <i className="leaf l1"></i><i className="leaf l2"></i><i className="leaf l3"></i><i className="leaf l4"></i>
      <div className="pot"></div><div className="plant-ground"></div>
      <span className="art-star a1">✳</span><span className="art-star a2">✧</span>
    </div>
  );
}

export function PageHeading({ kicker, title, description, children }) {
  return (
    <header className="page-heading">
      <div><span className="eyebrow">{kicker}</span><h1>{title}</h1><p>{description}</p></div>
      {children}
    </header>
  );
}

/** Without href the action button resets the recommendation filters. */
export function EmptyState({ title, text, href, label, level = 2, onReset }) {
  const Heading = 'h' + level;
  return (
    <div className="empty-state">
      <span className="round-icon" aria-hidden="true"><Icon name="leaf" /></span>
      <Heading>{title}</Heading>
      <p>{text}</p>
      {href
        ? <a href={'#' + href} className="btn">{label} <Icon name="arrow" /></a>
        : <button className="btn outline" data-action="reset-filters" onClick={onReset}>필터 초기화</button>}
    </div>
  );
}
