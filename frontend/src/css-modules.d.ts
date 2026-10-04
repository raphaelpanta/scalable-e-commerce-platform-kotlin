declare module '*.module.css' {
  // Lookups are `string | undefined` under noUncheckedIndexedAccess; compose with `@ui/cx`.
  const classes: Readonly<Record<string, string>>;
  export default classes;
}
