// Builds Blink Pocket into self-contained HTML files. Run: node build.js
//   dist/blink-mobile.html           one file with everything inside (works offline; open it in a phone browser)
//   dist/blink-mobile-artifact.html  the same app shaped for a claude.ai artifact page (three.js from cdnjs)
const fs = require('fs');
const path = require('path');
const read = p => fs.readFileSync(path.join(__dirname, p), 'utf8');
const safe = js => js.replace(/<\/script/gi, '<\\/script');

const css = read('src/style.css');
const body = read('src/body.html');
const blink = safe(read('src/blink.js'));
const app = safe(read('src/mobile.js'));
const three = safe(read('vendor/three.min.js'));

const titleAndStyle = `<title>Blink Pocket</title>\n<meta name="claude:viewport-fit-sides" content="cover">\n<style>\n${css}\n</style>\n`;
const scripts = threeTag => `${threeTag}\n<script>\n${blink}\n</script>\n<script>\n${app}\n</script>\n`;

fs.mkdirSync(path.join(__dirname, 'dist'), { recursive: true });

const standalone = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover,user-scalable=no">
<meta name="theme-color" content="#F6EEF8">
${titleAndStyle}</head>
<body>
${body}
${scripts(`<script>\n${three}\n</script>`)}</body>
</html>
`;
fs.writeFileSync(path.join(__dirname, 'dist', 'blink-mobile.html'), standalone);

const artifact = titleAndStyle + body + '\n' + scripts('<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>');
fs.writeFileSync(path.join(__dirname, 'dist', 'blink-mobile-artifact.html'), artifact);

for (const f of ['blink-mobile.html', 'blink-mobile-artifact.html']) {
  console.log(f, Math.round(fs.statSync(path.join(__dirname, 'dist', f)).size / 1024) + ' KB');
}
