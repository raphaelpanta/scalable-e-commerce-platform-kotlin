#!/usr/bin/env node
// Token enforcement (feature 007, research §4). Component CSS takes every colour and length from
// src/ui/styles/tokens.css through var(--...). Scans src/**/*.module.css and src/ui/styles/global.css
// and prints one `file:line value` per offence: colour literals, raw lengths outside var(--...) and
// http(s) URLs. tokens.css and fonts.css are never scanned. Exits 1 on any offence.
//
//   node scripts/check-design-tokens.mjs [rootDir]     rootDir defaults to the frontend directory
import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const NAMED_COLOURS = new Set(
  (
    'aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet brown ' +
    'burlywood cadetblue chartreuse chocolate coral cornflowerblue cornsilk crimson cyan darkblue darkcyan ' +
    'darkgoldenrod darkgray darkgreen darkgrey darkkhaki darkmagenta darkolivegreen darkorange darkorchid ' +
    'darkred darksalmon darkseagreen darkslateblue darkslategray darkslategrey darkturquoise darkviolet ' +
    'deeppink deepskyblue dimgray dimgrey dodgerblue firebrick floralwhite forestgreen fuchsia gainsboro ' +
    'ghostwhite gold goldenrod gray green greenyellow grey honeydew hotpink indianred indigo ivory khaki ' +
    'lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan lightgoldenrodyellow ' +
    'lightgray lightgreen lightgrey lightpink lightsalmon lightseagreen lightskyblue lightslategray ' +
    'lightslategrey lightsteelblue lightyellow lime limegreen linen magenta maroon mediumaquamarine ' +
    'mediumblue mediumorchid mediumpurple mediumseagreen mediumslateblue mediumspringgreen mediumturquoise ' +
    'mediumvioletred midnightblue mintcream mistyrose moccasin navajowhite navy oldlace olive olivedrab ' +
    'orange orangered orchid palegoldenrod palegreen paleturquoise palevioletred papayawhip peachpuff peru ' +
    'pink plum powderblue purple rebeccapurple red rosybrown royalblue saddlebrown salmon sandybrown ' +
    'seagreen seashell sienna silver skyblue slateblue slategray slategrey snow springgreen steelblue tan ' +
    'teal thistle tomato turquoise violet wheat white whitesmoke yellow yellowgreen'
  ).split(' '),
);

const HEX = /#[0-9a-fA-F]{3,8}\b/g;
const COLOUR_FUNCTION = /\b(?:rgba?|hsla?|hwb|oklch|oklab|lab|lch|color|color-mix)\(/g;
const LENGTH = /(?<![\w.-])-?(?:\d+\.?\d*|\.\d+)(?:px|rem|em|vh|vw|ch)\b/g;
const URL_LITERAL = /https?:/g;

/** Blank out comments but keep line breaks, so line numbers stay true. */
function stripComments(css) {
  return css.replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, ' '));
}

/** Every offending value on one line of CSS. */
function offencesOnLine(line) {
  const found = [...line.matchAll(URL_LITERAL)].map((match) => match[0]);
  if (/^\s*@/.test(line)) return found; // @media and friends: custom properties cannot be used there
  const code = line.replace(/var\(\s*--[\w-]+\s*\)/g, '');
  found.push(...(code.match(HEX) ?? []), ...(code.match(COLOUR_FUNCTION) ?? []));
  found.push(...(code.match(LENGTH) ?? []));
  const colon = code.indexOf(':');
  if (colon > 0 && !code.includes('{')) {
    const value = code.slice(colon + 1).replace(/(['"]).*?\1/g, '');
    for (const word of value.match(/[a-zA-Z]+/g) ?? []) {
      if (NAMED_COLOURS.has(word.toLowerCase())) found.push(word);
    }
  }
  return found;
}

/** Style files under `dir`, recursively. */
function cssFiles(dir) {
  const files = [];
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) files.push(...cssFiles(full));
    else if (entry.endsWith('.module.css')) files.push(full);
  }
  return files;
}

function main() {
  const defaultRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const root = path.resolve(process.argv[2] ?? defaultRoot);
  const srcDir = path.join(root, 'src');
  const files = [...(statSync(srcDir, { throwIfNoEntry: false }) ? cssFiles(srcDir) : [])];
  const globalCss = path.join(srcDir, 'ui', 'styles', 'global.css');
  if (statSync(globalCss, { throwIfNoEntry: false }) !== undefined) files.push(globalCss);

  const offences = [];
  for (const file of files.sort()) {
    const lines = stripComments(readFileSync(file, 'utf8')).split('\n');
    lines.forEach((line, index) => {
      for (const value of offencesOnLine(line)) {
        offences.push(
          `${path.relative(root, file).split(path.sep).join('/')}:${index + 1} ${value}`,
        );
      }
    });
  }
  if (offences.length > 0) {
    process.stderr.write(`${offences.join('\n')}\n`);
    process.stderr.write(`design tokens: ${offences.length} literal value(s) outside tokens.css\n`);
    process.exit(1);
  }
}

main();
