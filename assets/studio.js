/* Keep the static sites browsable directly from the downloaded repositories. */
if (location.protocol === 'file:') {
  const script = document.querySelector('script[src*="studio.js"]');
  const workspace = new URL('../../', script.src);
  document.querySelectorAll('a[data-local]').forEach(link => {
    link.href = new URL(link.dataset.local, workspace).href;
  });
}
const mobileSections = document.querySelector('[data-collapse-mobile]');
if (mobileSections && matchMedia('(max-width: 860px)').matches) {
  mobileSections.open = false;
}
