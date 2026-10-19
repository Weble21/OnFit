// Turns the OnFit logo into a link back to the app.
// Only the first login request carries the app's address (redirect_uri, already checked by Keycloak against the
// client's allowed URIs), so it is kept for this tab and reused on later pages such as a failed login or sign-up.
(function () {
  var KEY = 'onfit.appUrl';

  function stored() {
    try { return sessionStorage.getItem(KEY); } catch (e) { return null; }
  }

  function remember() {
    // The error page is also shown for a rejected redirect_uri, which must never become the link.
    if (document.body.dataset.pageId === 'login-error') return;
    var redirect = new URLSearchParams(location.search).get('redirect_uri');
    if (!redirect) return;
    try {
      var url = new URL(redirect);
      if (url.protocol === 'https:' || url.protocol === 'http:') sessionStorage.setItem(KEY, url.origin + url.pathname);
    } catch (e) { /* invalid URL or storage blocked: keep the logo as plain text */ }
  }

  document.addEventListener('DOMContentLoaded', function () {
    remember();
    var brand = document.getElementById('kc-header-wrapper');
    var href = stored();
    if (!brand || !href) return;
    var link = document.createElement('a');
    link.href = href;
    link.className = 'onfit-home';
    link.setAttribute('aria-label', '온핏 홈으로 돌아가기');
    while (brand.firstChild) link.appendChild(brand.firstChild);
    brand.appendChild(link);
  });
})();
