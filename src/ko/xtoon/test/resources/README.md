# Xtoon parser fixtures

Captured from public newxtoon1.com HTML/JSON on 2026-09-09, using Jina Reader only to obtain development evidence while direct site TLS connections failed locally. No Jina dependency is used in the extension.

- `list.html`: `/comics?sort=popular`, 23 comics plus one inline ad.
- `search.html`: `/search?q=ARCHE`.
- `detail.html`: `/comics/1876`, first 20 of 178 chapters.
- `chapters-2.json` through `chapters-9.json`: remaining pages of `/comics/1876/chapters?page=N`. Redundant HTML field removed.
- `chapter.html`: `/comics/1876/chapters/139884`, 146 image frames. Image error handlers retain the site's total-image hint; they are parsed as text and never executed.

Navigation, forms, CSRF values, scripts and irrelevant layout attributes were stripped. These fixtures validate parsing only, not Android connectivity, Cloudflare or app rendering.
