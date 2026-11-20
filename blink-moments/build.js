// Injects Blink's character code into the picker page. Run: node build.js  ->  blink-moments.html
const fs = require('fs'), path = require('path');
const page = fs.readFileSync(path.join(__dirname, 'src', 'page.html'), 'utf8');
const blink = fs.readFileSync(path.join(__dirname, '..', 'blink-mobile', 'src', 'blink.js'), 'utf8').replace(/<\/script/gi, '<\\/script');
fs.writeFileSync(path.join(__dirname, 'blink-moments.html'), page.replace('/*BLINK_JS*/', () => blink));
console.log('blink-moments.html', Math.round(fs.statSync(path.join(__dirname, 'blink-moments.html')).size / 1024) + ' KB');
