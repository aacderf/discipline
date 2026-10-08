// Deterministic, development-only icon export. Android builds consume checked-in XML.
import fs from 'node:fs';
import path from 'node:path';
import * as icons from 'lucide';
import { resampleIcon, buildPlan, interpPolar, allocOutputs, serialize } from 'morphicons';

const drawable = path.resolve('../../app/src/main/res/drawable');
const animator = path.resolve('../../app/src/main/res/animator');
fs.mkdirSync(drawable, { recursive: true });
fs.mkdirSync(animator, { recursive: true });
const xml = s => s.replaceAll('&', '&amp;').replaceAll('"', '&quot;');
const num = v => Number(v ?? 0);

function primitive(tag, a) {
  if (tag === 'path') return a.d;
  if (tag === 'line') return `M${a.x1} ${a.y1}L${a.x2} ${a.y2}`;
  if (tag === 'circle') {
    const x = num(a.cx), y = num(a.cy), r = num(a.r), k = +(r * .55228475).toFixed(5);
    return `M${x+r} ${y}C${x+r} ${y+k} ${x+k} ${y+r} ${x} ${y+r}C${x-k} ${y+r} ${x-r} ${y+k} ${x-r} ${y}C${x-r} ${y-k} ${x-k} ${y-r} ${x} ${y-r}C${x+k} ${y-r} ${x+r} ${y-k} ${x+r} ${y}Z`;
  }
  if (tag === 'rect') {
    const x = num(a.x), y = num(a.y), w = num(a.width), h = num(a.height);
    const r = Math.min(num(a.rx), w/2, h/2);
    if (!r) return `M${x} ${y}H${x+w}V${y+h}H${x}Z`;
    return `M${x+r} ${y}H${x+w-r}Q${x+w} ${y} ${x+w} ${y+r}V${y+h-r}Q${x+w} ${y+h} ${x+w-r} ${y+h}H${x+r}Q${x} ${y+h} ${x} ${y+h-r}V${y+r}Q${x} ${y} ${x+r} ${y}Z`;
  }
  if (tag === 'polyline' || tag === 'polygon') {
    const pts = a.points.trim().split(/\s+/).map(v => v.replace(',', ' '));
    return `M${pts.join('L')}${tag === 'polygon' ? 'Z' : ''}`;
  }
  throw new Error(`Unsupported Lucide primitive: ${tag}`);
}

const staticIcons = {
  nav_today: icons.House, nav_plans: icons.ListTodo, nav_focus: icons.Timer,
  nav_stats: icons.ChartNoAxesColumn, settings: icons.Settings2,
  map_pin: icons.MapPin, search: icons.Search, locate: icons.LocateFixed,
  reset: icons.RotateCcw, plus: icons.Plus, back: icons.ArrowLeft,
  target: icons.Target, shield: icons.ShieldCheck, clock: icons.Clock3,
  play: icons.Play, pause: icons.Pause, check: icons.Check,
  chevron_down: icons.ChevronDown, chevron_up: icons.ChevronUp,
};
for (const [name, data] of Object.entries(staticIcons)) {
  const paths = data.map(([tag, attrs]) => `  <path android:pathData="${xml(primitive(tag, attrs))}" android:fillColor="@android:color/transparent" android:strokeColor="#171B16" android:strokeWidth="1.8" android:strokeLineCap="round" android:strokeLineJoin="round"/>`).join('\n');
  fs.writeFileSync(path.join(drawable, `ic_${name}.xml`), `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n${paths}\n</vector>\n`);
}

const pairs = {
  play_pause: [icons.Play, icons.Pause],
  pause_play: [icons.Pause, icons.Play],
  expand_collapse: [icons.ChevronDown, icons.ChevronUp],
  collapse_expand: [icons.ChevronUp, icons.ChevronDown],
  pin_check: [icons.MapPin, icons.Check],
};
for (const [name, [from, to]] of Object.entries(pairs)) {
  const plan = buildPlan(resampleIcon(from), resampleIcon(to));
  const out = allocOutputs(plan);
  const at = t => { interpPolar(plan, t, out); return serialize(out, plan.items.map(i => i.closed)); };
  const start = at(0), end = at(1);
  const vectorName = `ic_morph_${name}_base`;
  const animatorName = `morph_${name}`;
  fs.writeFileSync(path.join(drawable, `${vectorName}.xml`), `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:name="morph" android:pathData="${xml(start)}" android:fillColor="@android:color/transparent" android:strokeColor="#171B16" android:strokeWidth="1.8" android:strokeLineCap="round" android:strokeLineJoin="round"/></vector>\n`);
  fs.writeFileSync(path.join(animator, `${animatorName}.xml`), `<objectAnimator xmlns:android="http://schemas.android.com/apk/res/android" android:propertyName="pathData" android:duration="260" android:valueFrom="${xml(start)}" android:valueTo="${xml(end)}" android:valueType="pathType"/>\n`);
  fs.writeFileSync(path.join(drawable, `ic_morph_${name}.xml`), `<animated-vector xmlns:android="http://schemas.android.com/apk/res/android" android:drawable="@drawable/${vectorName}"><target android:name="morph" android:animation="@animator/${animatorName}"/></animated-vector>\n`);
}
console.log(`Generated ${Object.keys(staticIcons).length} Lucide vectors and ${Object.keys(pairs).length} Morphicons pairs.`);
