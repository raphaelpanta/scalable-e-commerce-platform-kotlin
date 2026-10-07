import { ARIA_ROLES } from './policy.ts';

// What an interaction span says about its element (data-model.md §3.5): the ARIA role and the
// developer-assigned id, never the text, the value or the label. The span of a click inside a
// button is the button's (the nearest interactive ancestor), not the icon's.
export type ElementLike = {
  readonly tagName: string;
  readonly id: string;
  getAttribute(name: string): string | null;
  closest(selector: string): ElementLike | null;
};

const INTERACTIVE = 'button, a[href], input, select, textarea, form, summary, dialog, nav, [role]';

const ROLE_BY_TAG: Readonly<Record<string, string>> = {
  A: 'link',
  BUTTON: 'button',
  DIALOG: 'dialog',
  FORM: 'form',
  NAV: 'navigation',
  SELECT: 'combobox',
  SUMMARY: 'button',
  TEXTAREA: 'textbox',
};

const ROLE_BY_INPUT_TYPE: Readonly<Record<string, string>> = {
  button: 'button',
  checkbox: 'checkbox',
  radio: 'radio',
  reset: 'button',
  search: 'searchbox',
  submit: 'button',
};

/** The nearest interactive element at or above `element`, or the element itself. */
export function interactiveTarget(element: ElementLike): ElementLike {
  return element.closest(INTERACTIVE) ?? element;
}

/** The explicit ARIA role, else the role the tag implies; `undefined` for anything else. */
export function roleOf(element: ElementLike): string | undefined {
  const explicit = element.getAttribute('role')?.trim().toLowerCase();
  if (explicit !== undefined && ARIA_ROLES.includes(explicit)) return explicit;
  if (element.tagName === 'INPUT') {
    return ROLE_BY_INPUT_TYPE[element.getAttribute('type')?.toLowerCase() ?? 'text'] ?? 'textbox';
  }
  return ROLE_BY_TAG[element.tagName];
}
