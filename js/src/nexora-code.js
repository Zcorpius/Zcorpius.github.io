/* Nexora 风格代码块增强：顶栏（三色圆点 + 语言标签）+ 复制按钮
 * 作用对象：.post-body figure.highlight（Hexo 服务端 highlight.js 输出，保留行号结构）
 * 多时机触发 + MutationObserver 兜底，覆盖初始加载/动画/加密解密等各种情况 */
(function () {
  console.log('[nexora-code] script loaded, readyState:', document.readyState);

  function fallbackCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); } catch (e) {}
    document.body.removeChild(ta);
  }

  function enhanceFigure(figure) {
    if (figure.querySelector('.nx-code-header')) return false; // 已处理，跳过

    // 提取语言名（figure 的 class 形如 "highlight java"）
    var lang = '';
    var classes = figure.className.split(/\s+/);
    for (var j = 0; j < classes.length; j++) {
      if (classes[j] !== 'highlight') { lang = classes[j]; break; }
    }
    if (lang === 'plain' || lang === 'plaintext') lang = '';

    // 顶栏：三色圆点 + 语言标签 + 复制按钮
    var header = document.createElement('div');
    header.className = 'nx-code-header';

    var dots = document.createElement('span');
    dots.className = 'nx-dots';
    header.appendChild(dots);

    if (lang) {
      var langEl = document.createElement('span');
      langEl.className = 'nx-code-lang';
      langEl.textContent = lang;
      header.appendChild(langEl);
    }

    var btn = document.createElement('button');
    btn.className = 'nx-copy';
    btn.type = 'button';
    btn.textContent = '复制';
    header.appendChild(btn);

    // 把 table 包进 .nx-code-body（顶栏固定不滚动，代码区横向滚动，行号对齐不变）
    var table = figure.querySelector('table');
    var body = document.createElement('div');
    body.className = 'nx-code-body';
    if (table) {
      figure.insertBefore(body, table);
      body.appendChild(table);
    }

    // 顶栏插到 figure 最前
    figure.insertBefore(header, figure.firstChild);

    // 复制逻辑：从 td.code 取纯文本写剪贴板
    (function (figure, btn) {
      btn.addEventListener('click', function () {
        var codeEl = figure.querySelector('td.code');
        var text = codeEl ? codeEl.innerText : '';
        var done = function () {
          btn.textContent = '已复制 ✓';
          btn.classList.add('copied');
          setTimeout(function () {
            btn.textContent = '复制';
            btn.classList.remove('copied');
          }, 1500);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(done).catch(function () {
            fallbackCopy(text); done();
          });
        } else {
          fallbackCopy(text); done();
        }
      });
    })(figure, btn);
    return true;
  }

  function enhance() {
    var blocks = document.querySelectorAll('.post-body figure.highlight');
    var n = 0;
    for (var i = 0; i < blocks.length; i++) {
      if (enhanceFigure(blocks[i])) n++;
    }
    if (blocks.length) console.log('[nexora-code] figures:', blocks.length, 'newly enhanced:', n);
  }

  // 多时机触发：立即 / DOM 就绪 / 页面 load 完
  enhance();
  document.addEventListener('DOMContentLoaded', enhance);
  window.addEventListener('load', enhance);

  // 兜底：监听后续插入的代码块（加密文章解密、PJAX、延迟渲染等），防重复 + 防抖
  if ('MutationObserver' in window) {
    var timer = null;
    var mo = new MutationObserver(function () {
      if (timer) return;
      timer = setTimeout(function () { timer = null; enhance(); }, 200);
    });
    mo.observe(document.documentElement, { childList: true, subtree: true });
  }
})();
