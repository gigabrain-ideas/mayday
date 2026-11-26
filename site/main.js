(() => {
  const root = document.documentElement;
  const themeBtn = document.getElementById("theme");
  const icon = () => themeBtn.querySelector("use").setAttribute("href", root.classList.contains("dark") ? "#i-sun" : "#i-moon");
  icon();
  const system = matchMedia("(prefers-color-scheme: dark)");
  system.addEventListener("change", (e) => {
    try { localStorage.removeItem("mayday.theme"); } catch { /* storage blocked */ }
    root.classList.toggle("dark", e.matches);
    icon();
  });
  themeBtn.addEventListener("click", () => {
    root.classList.toggle("dark");
    try { localStorage.setItem("mayday.theme", root.classList.contains("dark") ? "dark" : "light"); } catch { /* storage blocked */ }
    icon();
  });

  document.querySelectorAll('[role="tablist"]').forEach((list) => {
    const tabs = [...list.querySelectorAll('[role="tab"]')];
    const select = (tab, focus) => {
      tabs.forEach((t) => {
        const on = t === tab;
        t.setAttribute("aria-selected", on);
        t.tabIndex = on ? 0 : -1;
        document.getElementById(t.getAttribute("aria-controls")).hidden = !on;
      });
      if (focus) tab.focus();
    };
    tabs.forEach((t, i) => {
      t.addEventListener("click", () => select(t));
      t.addEventListener("keydown", (e) => {
        const step = { ArrowRight: 1, ArrowLeft: -1 }[e.code];
        if (step) { e.preventDefault(); select(tabs[(i + step + tabs.length) % tabs.length], true); }
      });
    });
  });


  const reveal = document.querySelectorAll(".reveal");
  if (!("IntersectionObserver" in window)) { reveal.forEach((el) => el.classList.add("seen")); return; }
  const io = new IntersectionObserver((entries) => entries.forEach((e) => {
    if (e.isIntersecting) { e.target.classList.add("seen"); io.unobserve(e.target); }
  }), { rootMargin: "0px 0px -8% 0px" });
  reveal.forEach((el) => io.observe(el));
})();
