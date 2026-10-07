import { describe, expect, it } from 'vitest';

import { interactiveTarget, roleOf } from '@telemetry/elements';

function parse(html: string): HTMLElement {
  const parsed = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html');
  const container = document.adoptNode(parsed.body.firstElementChild as HTMLElement);
  document.body.append(container);
  return container;
}

describe('roleOf', () => {
  it.each([
    ['<button id="x">Buy</button>', 'button'],
    ['<a href="/cart" id="x">Cart</a>', 'link'],
    ['<select id="x"></select>', 'combobox'],
    ['<textarea id="x"></textarea>', 'textbox'],
    ['<form id="x"></form>', 'form'],
    ['<nav id="x"></nav>', 'navigation'],
    ['<summary id="x">More</summary>', 'button'],
    ['<dialog id="x"></dialog>', 'dialog'],
    ['<input id="x" type="checkbox">', 'checkbox'],
    ['<input id="x" type="radio">', 'radio'],
    ['<input id="x" type="submit">', 'button'],
    ['<input id="x" type="button">', 'button'],
    ['<input id="x" type="reset">', 'button'],
    ['<input id="x" type="search">', 'searchbox'],
    ['<input id="x" type="email">', 'textbox'],
    ['<input id="x">', 'textbox'],
    ['<div id="x" role="Tab">Tab</div>', 'tab'],
    ['<button id="x" role="menuitem">Item</button>', 'menuitem'],
  ])('%s is a %s', (html, role) => {
    expect(roleOf(parse(html).querySelector('#x')!)).toBe(role);
  });

  it('has no role for a plain element or an unknown explicit role', () => {
    expect(roleOf(parse('<div id="x">text</div>').querySelector('#x')!)).toBeUndefined();
    expect(
      roleOf(parse('<div id="x" role="ana@example.com">t</div>').querySelector('#x')!),
    ).toBeUndefined();
  });
});

describe('interactiveTarget', () => {
  it('is the nearest interactive ancestor of the clicked element', () => {
    const root = parse('<button id="buy"><span><b id="inner">Buy now</b></span></button>');
    expect(interactiveTarget(root.querySelector('#inner')!).id).toBe('buy');
    const link = parse('<a href="/x" id="l"><img alt="a"></a>');
    expect(interactiveTarget(link.querySelector('img') as HTMLElement).id).toBe('l');
    const withRole = parse('<div role="tab" id="t"><span id="s">x</span></div>');
    expect(interactiveTarget(withRole.querySelector('#s')!).id).toBe('t');
  });

  it('is the element itself when nothing interactive is above it', () => {
    const root = parse('<p id="plain">text</p>');
    const paragraph = root.querySelector('#plain')!;
    expect(interactiveTarget(paragraph)).toBe(paragraph);
  });
});
