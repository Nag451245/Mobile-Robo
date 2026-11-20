// Tiny static server for testing Blink Pocket on this computer: node serve.js  ->  http://localhost:5177/
const http = require('http'), fs = require('fs'), path = require('path');
const root = path.join(__dirname, 'dist'), port = Number(process.env.PORT || 5177);
http.createServer((req, res) => {
  const name = decodeURIComponent((req.url || '/').split('?')[0]).replace(/^\/+/, '') || 'blink-mobile.html';
  const file = path.join(root, path.normalize(name));
  if (!file.startsWith(root) || !fs.existsSync(file)) { res.writeHead(404); return res.end('Not found'); }
  res.writeHead(200, { 'Content-Type': file.endsWith('.html') ? 'text/html; charset=utf-8' : 'application/octet-stream', 'Cache-Control': 'no-store' });
  fs.createReadStream(file).pipe(res);
}).listen(port, () => console.log(`Blink Pocket on http://localhost:${port}/`));
