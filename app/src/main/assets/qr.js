/* A small QR code writer, enough for the short link the frame shows for phone setup.
   Byte mode, error correction level M, versions 1 to 6 (up to 106 bytes). No dependencies, works offline.
   window.SalahQR.svg(text) returns an SVG string: dark modules on white, with the four-module quiet zone. */
(function () {
  'use strict';
  /* per version: [error-correction codewords per block, number of blocks, data codewords per block] at level M */
  var VER = [null, [10, 1, 16], [16, 1, 28], [26, 1, 44], [18, 2, 32], [24, 2, 43], [16, 4, 27]];
  var ALIGN = [null, [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34]];
  /* GF(256), polynomial 0x11d */
  var EXP = [], LOG = [];
  (function () { var x = 1; for (var i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 256) x ^= 0x11d; } for (i = 255; i < 512; i++) EXP[i] = EXP[i - 255]; })();
  function mul(a, b) { return a && b ? EXP[LOG[a] + LOG[b]] : 0; }
  function rsGen(n) { var g = [1]; for (var i = 0; i < n; i++) { var r = []; for (var j = 0; j <= g.length; j++) r[j] = (j < g.length ? mul(g[j], EXP[i]) : 0) ^ (j ? g[j - 1] : 0); g = r; } return g.reverse(); }
  function rsRem(data, n) { var g = rsGen(n), r = []; for (var i = 0; i < n; i++) r[i] = 0;
    for (i = 0; i < data.length; i++) { var f = data[i] ^ r.shift(); r.push(0); for (var j = 0; j < n; j++) r[j] ^= mul(g[j + 1], f); } return r; }
  function utf8(s) { var o = [], e = unescape(encodeURIComponent(s)); for (var i = 0; i < e.length; i++) o.push(e.charCodeAt(i)); return o; }

  function codewords(text) {
    var bytes = utf8(text), v = 1; while (v <= 6 && bytes.length > VER[v][1] * VER[v][2] - 2) v++;
    if (v > 6) throw new Error('too long for a QR code here');
    var spec = VER[v], total = spec[1] * spec[2], bits = [];
    function put(val, n) { for (var i = n - 1; i >= 0; i--) bits.push((val >> i) & 1); }
    put(4, 4); put(bytes.length, 8); bytes.forEach(function (b) { put(b, 8); });
    for (var t = 0; t < 4 && bits.length < total * 8; t++) bits.push(0);
    while (bits.length % 8) bits.push(0);
    var data = []; for (var i = 0; i < bits.length; i += 8) { var b = 0; for (var k = 0; k < 8; k++) b = (b << 1) | bits[i + k]; data.push(b); }
    for (var p = 0; data.length < total; p++) data.push(p % 2 ? 0x11 : 0xec);
    var blocks = [], ecs = [];
    for (i = 0; i < spec[1]; i++) { var d = data.slice(i * spec[2], (i + 1) * spec[2]); blocks.push(d); ecs.push(rsRem(d, spec[0])); }
    var out = []; for (i = 0; i < spec[2]; i++) blocks.forEach(function (bl) { out.push(bl[i]); });
    for (i = 0; i < spec[0]; i++) ecs.forEach(function (e) { out.push(e[i]); });
    return { v: v, words: out };
  }

  function matrix(text) {
    var cw = codewords(text), v = cw.v, n = 17 + 4 * v, m = [], fn = [], r, c;
    for (r = 0; r < n; r++) { m[r] = []; fn[r] = []; for (c = 0; c < n; c++) { m[r][c] = 0; fn[r][c] = 0; } }
    function set(rr, cc, val) { if (rr < 0 || cc < 0 || rr >= n || cc >= n) return; m[rr][cc] = val ? 1 : 0; fn[rr][cc] = 1; }
    function finder(r0, c0) { for (var i = -1; i <= 7; i++) for (var j = -1; j <= 7; j++) {
      var on = i >= 0 && i <= 6 && j >= 0 && j <= 6 && (i === 0 || i === 6 || j === 0 || j === 6 || (i >= 2 && i <= 4 && j >= 2 && j <= 4)); set(r0 + i, c0 + j, on); } }
    finder(0, 0); finder(0, n - 7); finder(n - 7, 0);
    for (var i = 8; i < n - 8; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
    var al = ALIGN[v];
    al.forEach(function (ar) { al.forEach(function (ac) { if (fn[ar][ac]) return;
      for (var a = -2; a <= 2; a++) for (var b = -2; b <= 2; b++) set(ar + a, ac + b, Math.max(Math.abs(a), Math.abs(b)) !== 1); }); });
    /* reserve the format areas, and the dark module */
    for (i = 0; i < 9; i++) { if (!fn[8][i]) set(8, i, 0); if (!fn[i][8]) set(i, 8, 0); }
    for (i = 0; i < 8; i++) { set(8, n - 1 - i, 0); set(n - 1 - i, 8, 0); }
    set(n - 8, 8, 1);
    /* data, in the upward and downward zigzag from the bottom right */
    var bits = []; cw.words.forEach(function (w) { for (var k = 7; k >= 0; k--) bits.push((w >> k) & 1); });
    var bi = 0, up = true;
    for (c = n - 1; c > 0; c -= 2) { if (c === 6) c--; for (var s = 0; s < n; s++) { r = up ? n - 1 - s : s;
      for (var d = 0; d < 2; d++) { var cc = c - d; if (fn[r][cc]) continue; m[r][cc] = bi < bits.length ? bits[bi] : 0; bi++; } } up = !up; }
    var MASK = [function (y, x) { return (y + x) % 2 === 0; }, function (y) { return y % 2 === 0; }, function (y, x) { return x % 3 === 0; }, function (y, x) { return (y + x) % 3 === 0; },
      function (y, x) { return (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0; }, function (y, x) { return (y * x) % 2 + (y * x) % 3 === 0; },
      function (y, x) { return ((y * x) % 2 + (y * x) % 3) % 2 === 0; }, function (y, x) { return ((y + x) % 2 + (y * x) % 3) % 2 === 0; }];
    function format(g, k) { var data = (0 << 3) | k, rem = data;          /* level M is 00 */
      for (var i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >> 9) * 0x537);
      var f = ((data << 10) | rem) ^ 0x5412, bit = function (i) { return (f >> i) & 1; };
      for (i = 0; i <= 5; i++) g[8][i] = bit(14 - i) ? 1 : 0;                 /* around the top-left finder: along row 8 ... */
      g[8][7] = bit(8); g[8][8] = bit(7); g[7][8] = bit(6);
      for (i = 0; i <= 5; i++) g[5 - i][8] = bit(5 - i);                      /* ... and up column 8 */
      for (i = 0; i <= 6; i++) g[n - 1 - i][8] = bit(14 - i);                 /* the copy: up from the bottom-left ... */
      for (i = 0; i <= 7; i++) g[8][n - 8 + i] = bit(7 - i);                  /* ... and along to the top-right */
      g[n - 8][8] = 1; }
    function masked(k) { var g = m.map(function (row) { return row.slice(); });
      for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) if (!fn[y][x] && MASK[k](y, x)) g[y][x] ^= 1;
      format(g, k); return g; }
    function penalty(g) { var p = 0, y, x, k;
      for (y = 0; y < n; y++) for (k = 0; k < 2; k++) { var run = 1; for (x = 1; x < n; x++) { var a = k ? g[x][y] : g[y][x], b = k ? g[x - 1][y] : g[y][x - 1];
        if (a === b) { run++; if (x === n - 1 && run >= 5) p += run - 2; } else { if (run >= 5) p += run - 2; run = 1; } } }
      for (y = 0; y < n - 1; y++) for (x = 0; x < n - 1; x++) if (g[y][x] === g[y][x + 1] && g[y][x] === g[y + 1][x] && g[y][x] === g[y + 1][x + 1]) p += 3;
      var pat = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0], tap = pat.slice().reverse();
      for (y = 0; y < n; y++) for (x = 0; x <= n - 11; x++) for (k = 0; k < 2; k++) { var h1 = true, h2 = true;
        for (var i = 0; i < 11; i++) { var val = k ? g[x + i][y] : g[y][x + i]; if (val !== pat[i]) h1 = false; if (val !== tap[i]) h2 = false; } if (h1) p += 40; if (h2) p += 40; }
      var dark = 0; for (y = 0; y < n; y++) for (x = 0; x < n; x++) dark += g[y][x];
      return p + Math.floor(Math.abs(dark * 100 / (n * n) - 50) / 5) * 10; }
    var best = null, bestP = Infinity;
    for (var k = 0; k < 8; k++) { var g = masked(k), p = penalty(g); if (p < bestP) { bestP = p; best = g; } }
    return best;
  }

  function svg(text) { var g = matrix(text), n = g.length, q = 4, size = n + q * 2, d = '';
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) if (g[y][x]) d += 'M' + (x + q) + ' ' + (y + q) + 'h1v1h-1z';
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + size + ' ' + size + '" shape-rendering="crispEdges" role="img"><rect width="' + size + '" height="' + size + '" fill="#fff"/><path d="' + d + '" fill="#000"/></svg>'; }

  var api = { svg: svg, matrix: matrix };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else window.SalahQR = api;
})();
