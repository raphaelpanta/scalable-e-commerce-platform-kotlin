// Reads the email sink of the Compose stack through Mailpit's API (`/api/v1/search`,
// `/api/v1/message/{id}`), mirroring acceptance/.../support/MailpitClient.kt. The token query
// parameter name (`token`) is shared with the JVM client and the notification templates.
export type MailMessage = {
  readonly id: string;
  readonly subject: string;
  readonly text: string;
  readonly html: string;
};

const TOKEN_IN_LINK = /[?&]token=([A-Za-z0-9._~%-]+)/;
const TOKEN_IN_TEXT = /\btoken\b\W{1,3}([A-Za-z0-9._~-]{8,})/i;
const LINK = /https?:\/\/[^\s"'<>]+/g;

type SearchResponse = { readonly messages?: ReadonlyArray<{ readonly ID: string }> };
type MessageResponse = {
  readonly ID: string;
  readonly Subject?: string;
  readonly Text?: string;
  readonly HTML?: string;
};

export class MailpitClient {
  readonly #baseUrl: string;

  constructor(baseUrl: string) {
    this.#baseUrl = baseUrl.replace(/\/$/, '');
  }

  async idsTo(address: string): Promise<Set<string>> {
    const query = encodeURIComponent(`to:"${address}"`);
    const response = await fetch(`${this.#baseUrl}/api/v1/search?query=${query}&limit=50`);
    if (!response.ok) throw new Error(`Mailpit search failed with status ${response.status}`);
    const body = (await response.json()) as SearchResponse;
    return new Set((body.messages ?? []).map((message) => message.ID));
  }

  async message(id: string): Promise<MailMessage> {
    const response = await fetch(`${this.#baseUrl}/api/v1/message/${encodeURIComponent(id)}`);
    if (!response.ok)
      throw new Error(`Mailpit message ${id} failed with status ${response.status}`);
    const body = (await response.json()) as MessageResponse;
    return {
      id: body.ID,
      subject: body.Subject ?? '',
      text: body.Text ?? '',
      html: body.HTML ?? '',
    };
  }

  /** Waits for a message to `address` that is not in `known` and satisfies `accept`. */
  async awaitMessageTo(
    address: string,
    known: ReadonlySet<string>,
    accept: (message: MailMessage) => boolean = () => true,
    timeoutMs = 30_000,
  ): Promise<MailMessage> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const ids = await this.idsTo(address);
      for (const id of ids) {
        if (known.has(id)) continue;
        const message = await this.message(id);
        if (accept(message)) return message;
      }
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    throw new Error(`no new message to ${address} within ${timeoutMs} ms`);
  }

  /** Waits for a verification or password-reset message and returns its token. */
  async awaitToken(address: string, known: ReadonlySet<string>): Promise<string> {
    const message = await this.awaitMessageTo(address, known, (m) => tokenIn(m) !== undefined);
    const token = tokenIn(message);
    if (token === undefined) throw new Error('message carries no token');
    return token;
  }
}

export function tokenIn(message: MailMessage): string | undefined {
  for (const haystack of [message.html, message.text]) {
    const inLink = TOKEN_IN_LINK.exec(haystack);
    if (inLink?.[1] !== undefined) return decodeURIComponent(inLink[1]);
  }
  const inText = TOKEN_IN_TEXT.exec(message.text);
  return inText?.[1];
}

export function linksIn(message: MailMessage): string[] {
  return [...new Set(`${message.html}\n${message.text}`.match(LINK) ?? [])];
}
