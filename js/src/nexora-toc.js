/* Nexora 风格 TOC scroll-spy：用 IntersectionObserver 监听标题可见性，
 * 滚动时把侧边栏目录高亮到当前阅读标题。
 * 取代 NexT 原本的 Bootstrap offset-based scrollspy——后者在图片懒加载 /
 * 布局变化时定位失准，导致高亮不随滚动更新。
 * 作用对象：.post-toc（侧边栏目录）+ .post-body 内带 id 的标题 */
(function () {
  function init() {
    var toc = document.querySelector('.post-toc');
    if (!toc || !('IntersectionObserver' in window)) return;

    // 建立 anchor -> toc 链接 的映射，并按文档顺序收集标题
    var linkMap = {};
    var headings = [];
    var links = toc.querySelectorAll('a.nav-link');
    for (var i = 0; i < links.length; i++) {
      var href = links[i].getAttribute('href') || '';
      if (href.charAt(0) !== '#') continue;
      var id = decodeURIComponent(href.slice(1));
      var h = document.getElementById(id);
      if (h) { linkMap[id] = links[i]; headings.push(h); }
    }
    if (!headings.length) return;

    var items = toc.querySelectorAll('.nav-item');
    function setActive(id) {
      for (var k = 0; k < items.length; k++) {
        items[k].classList.remove('active', 'active-current');
      }
      var link = linkMap[id];
      if (link) {
        var li = link.closest('.nav-item');
        if (li) li.classList.add('active', 'active-current');
      }
    }

    var visibleIds = [];
    var observer = new IntersectionObserver(function (entries) {
      for (var i = 0; i < entries.length; i++) {
        var id = entries[i].target.id;
        var idx = visibleIds.indexOf(id);
        if (entries[i].isIntersecting) {
          if (idx === -1) visibleIds.push(id);
        } else if (idx !== -1) {
          visibleIds.splice(idx, 1);
        }
      }
      // 取可见标题中文档顺序最靠前的一个
      var topId = null;
      for (var j = 0; j < headings.length; j++) {
        if (visibleIds.indexOf(headings[j].id) !== -1) { topId = headings[j].id; break; }
      }
      if (topId) setActive(topId);
      // visibleIds 为空时保留上一次高亮（标题间距大时不闪烁）
    }, { rootMargin: '-60px 0px -78% 0px', threshold: 0 });

    for (var m = 0; m < headings.length; m++) observer.observe(headings[m]);

    // 点击目录项：平滑滚动到标题（覆盖默认锚点跳转，减去顶部 header 高度）
    Object.keys(linkMap).forEach(function (id) {
      linkMap[id].addEventListener('click', function (e) {
        var h = document.getElementById(id);
        if (!h) return;
        e.preventDefault();
        window.scrollTo({
          top: h.getBoundingClientRect().top + window.pageYOffset - 60,
          behavior: 'smooth'
        });
      });
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
