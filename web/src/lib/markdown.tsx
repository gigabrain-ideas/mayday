import { Fragment, type ReactNode } from "react";

/** Small safe Markdown renderer for runbooks and agent output: headings, lists, code, bold, links. No raw HTML. */
function inline(text: string, key: string): ReactNode[] {
  const out: ReactNode[] = [];
  const re = /(`[^`]+`|\*\*[^*]+\*\*|\[[^\]]+\]\((https?:\/\/[^)\s]+)\))/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let i = 0;
  while ((m = re.exec(text))) {
    if (m.index > last) out.push(text.slice(last, m.index));
    const t = m[0];
    const k = `${key}-${i++}`;
    if (t.startsWith("`")) out.push(<code key={k}>{t.slice(1, -1)}</code>);
    else if (t.startsWith("**")) out.push(<strong key={k}>{t.slice(2, -2)}</strong>);
    else {
      const label = t.slice(1, t.indexOf("]"));
      out.push(<a key={k} href={m[2]} target="_blank" rel="noreferrer noopener" className="underline underline-offset-2">{label}</a>);
    }
    last = m.index + t.length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

export function Markdown({ text }: { text: string }) {
  const lines = text.replace(/\r/g, "").split("\n");
  const blocks: ReactNode[] = [];
  let i = 0;
  let n = 0;
  while (i < lines.length) {
    const line = lines[i];
    const key = `b${n++}`;
    if (line.startsWith("```")) {
      const code: string[] = [];
      i++;
      while (i < lines.length && !lines[i].startsWith("```")) code.push(lines[i++]);
      i++;
      blocks.push(<pre key={key}><code>{code.join("\n")}</code></pre>);
      continue;
    }
    const h = /^(#{1,3})\s+(.*)$/.exec(line);
    if (h) {
      const Tag = (["h1", "h2", "h3"] as const)[h[1].length - 1];
      blocks.push(<Tag key={key}>{inline(h[2], key)}</Tag>);
      i++;
      continue;
    }
    if (/^\s*(\d+\.|[-*])\s+/.test(line)) {
      const ordered = /^\s*\d+\./.test(line);
      const items: string[] = [];
      while (i < lines.length && /^\s*(\d+\.|[-*])\s+/.test(lines[i])) items.push(lines[i++].replace(/^\s*(\d+\.|[-*])\s+/, ""));
      const List = ordered ? "ol" : "ul";
      blocks.push(<List key={key}>{items.map((it, j) => <li key={j}>{inline(it, `${key}-${j}`)}</li>)}</List>);
      continue;
    }
    if (!line.trim()) {
      i++;
      continue;
    }
    const para: string[] = [];
    while (i < lines.length && lines[i].trim() && !/^(#{1,3}\s|```|\s*(\d+\.|[-*])\s)/.test(lines[i])) para.push(lines[i++]);
    blocks.push(<p key={key}>{para.map((p, j) => <Fragment key={j}>{j > 0 && <br />}{inline(p, `${key}-${j}`)}</Fragment>)}</p>);
  }
  return <div className="prose-runbook">{blocks}</div>;
}
