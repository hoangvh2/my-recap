import { addDays, addMonths, parseDateLoose, type ISODate } from "../../../shared/dates";
import { DEFAULT_IMPORT_COLUMNS, IMPORT_FIELDS, type ImportField, type License, type LicenseKind } from "../../../shared/model";
import { fold } from "../../../shared/text";
import { parseVnd } from "./money";

/**
 * Importing licences from a spreadsheet: paste cells copied from Excel (tab separated) or open a CSV
 * file. Column titles are matched against the ones set in Settings first, then against common Vietnamese
 * and English names, so most sheets work without any setup.
 */

/** Splits pasted text into cells. Tab, semicolon or comma separated; "quoted, cells" and "" escapes supported. */
export function parseTable(text: string): string[][] {
  const src = text.replace(/^﻿/, "");
  const first = src.split(/\r?\n/, 1)[0] ?? "";
  const count = (c: string) => first.split(c).length - 1;
  const delimiter = count("\t") > 0 ? "\t" : count(";") > count(",") ? ";" : ",";
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = "";
  let quoted = false;
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (quoted) {
      if (ch === '"' && src[i + 1] === '"') {
        cell += '"';
        i++;
      } else if (ch === '"') {
        quoted = false;
      } else {
        cell += ch;
      }
    } else if (ch === '"' && cell === "") {
      quoted = true;
    } else if (ch === delimiter) {
      row.push(cell);
      cell = "";
    } else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && src[i + 1] === "\n") i++;
      row.push(cell);
      cell = "";
      rows.push(row);
      row = [];
    } else {
      cell += ch;
    }
  }
  if (cell !== "" || row.length > 0) {
    row.push(cell);
    rows.push(row);
  }
  return rows.filter((r) => r.some((c) => c.trim() !== "")).map((r) => r.map((c) => c.trim()));
}

/** Other names people give each column, tried after the ones from Settings. */
const SYNONYMS: Record<ImportField, string[]> = {
  customer: ["khách hàng", "tên khách", "tên khách hàng", "công ty", "đơn vị", "customer", "client", "company"],
  contact: ["người liên hệ", "liên hệ", "đầu mối", "contact"],
  phone: ["số điện thoại", "điện thoại", "sđt", "sdt", "phone", "mobile"],
  email: ["email", "e-mail", "thư điện tử"],
  product: ["sản phẩm", "tên sản phẩm", "phần mềm", "dịch vụ", "product", "item"],
  kind: ["loại", "loại hình", "hình thức", "type"],
  startDate: ["ngày bắt đầu", "bắt đầu", "ngày mua", "ngày kích hoạt", "ngày ký", "start", "start date"],
  endDate: ["ngày hết hạn", "hết hạn", "hạn", "ngày kết thúc", "kết thúc", "expiry", "end date", "expire"],
  termMonths: ["thời hạn (tháng)", "thời hạn", "số tháng", "term"],
  value: ["giá trị", "giá", "doanh thu", "thành tiền", "amount", "value", "price"],
  contractNo: ["số hợp đồng", "hợp đồng", "số hđ", "mã hợp đồng", "contract"],
  note: ["ghi chú", "note", "notes"],
};

export interface ColumnMap {
  /** Column index for each field that was found. */
  index: Partial<Record<ImportField, number>>;
  /** Headers that matched nothing. */
  unknown: string[];
}

const aliases = (title: string): string[] => title.split(",").map((s) => fold(s)).filter(Boolean);

/** Decides which column is which. A configured title beats a built-in name; exact beats "contains". */
export function mapColumns(headers: string[], configured: Record<ImportField, string>): ColumnMap {
  const folded = headers.map(fold);
  const index: Partial<Record<ImportField, number>> = {};
  const used = new Set<number>();
  const pass = (matcher: (h: string, a: string) => boolean, source: (f: ImportField) => string[]) => {
    for (const f of IMPORT_FIELDS) {
      if (index[f] !== undefined) continue;
      for (const a of source(f)) {
        const i = folded.findIndex((h, k) => !used.has(k) && h !== "" && matcher(h, a));
        if (i >= 0) {
          index[f] = i;
          used.add(i);
          break;
        }
      }
    }
  };
  const mine = (f: ImportField) => aliases(configured[f] ?? DEFAULT_IMPORT_COLUMNS[f]);
  const builtin = (f: ImportField) => SYNONYMS[f].map(fold);
  const exact = (h: string, a: string) => h === a;
  const contains = (h: string, a: string) => a.length >= 3 && h.includes(a);
  pass(exact, mine);
  pass(exact, builtin);
  pass(contains, mine);
  pass(contains, builtin);
  return { index, unknown: headers.filter((_, i) => !used.has(i) && headers[i].trim() !== "") };
}

export function parseKind(text: string): LicenseKind {
  const f = fold(text);
  if (f.includes("bao hanh") || f.includes("warranty")) return "WARRANTY";
  if (f.includes("bao tri") || f.includes("maintenance") || f.includes("support")) return "MAINTENANCE";
  if (f.includes("thue bao") || f.includes("subscription")) return "SUBSCRIPTION";
  return "LICENSE";
}

/** "12", "12 tháng", "1 năm", "2 years" → months. */
export function parseTerm(text: string): number | undefined {
  const m = /(\d+(?:[.,]\d+)?)\s*(nam|year|y|thang|month|m)?/.exec(fold(text));
  if (!m) return undefined;
  const n = Number(m[1].replace(",", "."));
  const months = m[2] && ["nam", "year", "y"].includes(m[2]) ? Math.round(n * 12) : Math.round(n);
  return months >= 1 && months <= 120 ? months : undefined;
}

export type RowStatus = "ok" | "error" | "duplicate";

export interface ImportRow {
  /** Line in the pasted text (the header is line 1). */
  line: number;
  status: RowStatus;
  problems: string[];
  notes: string[];
  customer: string;
  contact?: string;
  phone?: string;
  email?: string;
  product: string;
  kind: LicenseKind;
  startDate?: ISODate;
  endDate?: ISODate;
  termMonths?: number;
  value?: number;
  contractNo?: string;
  note?: string;
}

export interface ImportPlan {
  rows: ImportRow[];
  /** Fields that are needed but have no column. */
  missing: ImportField[];
  columns: ColumnMap;
  /** Customers named in the sheet that do not exist yet (by folded name). */
  newCustomers: string[];
  matchedCustomers: number;
}

const REQUIRED: ImportField[] = ["customer", "product"];

/**
 * Turns pasted text into rows ready to save, with a plain-language reason for every row that cannot be.
 * `existing` lets it spot customers that already exist and licences that are already there.
 */
export function planImport(
  text: string,
  configured: Record<ImportField, string>,
  existing: { customers: { id: string; name: string }[]; licenses: Pick<License, "customerId" | "product" | "endDate">[] },
): ImportPlan {
  const table = parseTable(text);
  const empty: ImportPlan = { rows: [], missing: [], columns: { index: {}, unknown: [] }, newCustomers: [], matchedCustomers: 0 };
  if (table.length === 0) return empty;
  const columns = mapColumns(table[0], configured);
  const missing = REQUIRED.filter((f) => columns.index[f] === undefined);
  if (columns.index.endDate === undefined && !(columns.index.startDate !== undefined && columns.index.termMonths !== undefined)) missing.push("endDate");
  if (missing.length > 0) return { ...empty, missing, columns };

  const cell = (r: string[], f: ImportField) => (columns.index[f] === undefined ? "" : (r[columns.index[f]!] ?? "").trim());
  const customerByName = new Map(existing.customers.map((c) => [fold(c.name), c.id]));
  const seen = new Set(existing.licenses.map((l) => `${l.customerId}|${fold(l.product)}|${l.endDate}`));
  const fresh = new Set<string>();
  const rows: ImportRow[] = [];
  let matched = 0;
  const matchedNames = new Set<string>();

  table.slice(1).forEach((r, i) => {
    const problems: string[] = [];
    const notes: string[] = [];
    const customer = cell(r, "customer");
    const product = cell(r, "product");
    if (!customer) problems.push("Thiếu tên khách");
    if (!product) problems.push("Thiếu sản phẩm");

    const startRaw = cell(r, "startDate");
    const start = startRaw ? parseDateLoose(startRaw) : null;
    if (startRaw && !start) notes.push(`Bỏ qua ngày bắt đầu "${startRaw}" vì không đọc được`);
    const term = cell(r, "termMonths") ? parseTerm(cell(r, "termMonths")) : undefined;

    const endRaw = cell(r, "endDate");
    let end: ISODate | null = endRaw ? parseDateLoose(endRaw) : null;
    if (endRaw && !end) problems.push(`Ngày hết hạn "${endRaw}" không đọc được (dùng dạng 15/03/2027)`);
    if (!endRaw && start && term) {
      end = addDays(addMonths(start, term), -1);
      notes.push("Ngày hết hạn tính từ ngày bắt đầu và thời hạn");
    }
    if (!end && !endRaw) problems.push("Thiếu ngày hết hạn");
    if (start && end && start > end) problems.push("Ngày bắt đầu sau ngày hết hạn");

    const valueRaw = cell(r, "value");
    const value = valueRaw ? parseVnd(valueRaw) : null;
    if (valueRaw && value === null) notes.push(`Bỏ qua giá trị "${valueRaw}" vì không đọc được`);

    let status: RowStatus = problems.length > 0 ? "error" : "ok";
    if (status === "ok") {
      const key = fold(customer);
      const known = customerByName.get(key);
      const dup = `${known ?? `new:${key}`}|${fold(product)}|${end}`;
      if (seen.has(dup) || fresh.has(dup)) {
        status = "duplicate";
        notes.push("Đã có license này, sẽ bỏ qua");
      } else {
        fresh.add(dup);
        if (known) matchedNames.add(key);
      }
    }
    rows.push({
      line: i + 2,
      status,
      problems,
      notes,
      customer,
      contact: cell(r, "contact") || undefined,
      phone: cell(r, "phone") || undefined,
      email: cell(r, "email") || undefined,
      product,
      kind: parseKind(cell(r, "kind")),
      startDate: start ?? undefined,
      endDate: end ?? undefined,
      termMonths: term,
      value: value ?? undefined,
      contractNo: cell(r, "contractNo") || undefined,
      note: cell(r, "note") || undefined,
    });
  });

  matched = matchedNames.size;
  const newNames = new Map<string, string>();
  for (const r of rows) {
    if (r.status !== "ok") continue;
    const key = fold(r.customer);
    if (!customerByName.has(key) && !newNames.has(key)) newNames.set(key, r.customer);
  }
  return { rows, missing, columns, newCustomers: [...newNames.values()], matchedCustomers: matched };
}

/** A starter file with the default column titles and one example row; BOM so Excel shows Vietnamese correctly. */
export function templateCsv(configured: Record<ImportField, string> = DEFAULT_IMPORT_COLUMNS): string {
  const q = (s: string) => (/[",\n]/.test(s) ? `"${s.replaceAll('"', '""')}"` : s);
  const title = (f: ImportField) => (configured[f] ?? DEFAULT_IMPORT_COLUMNS[f]).split(",")[0].trim();
  const example: Record<ImportField, string> = {
    customer: "Công ty ABC", contact: "Anh Nam", phone: "0912345678", email: "", product: "Phần mềm kế toán", kind: "License",
    startDate: "16/03/2026", endDate: "15/03/2027", termMonths: "12", value: "120000000", contractNo: "HD-001", note: "",
  };
  return `﻿${IMPORT_FIELDS.map((f) => q(title(f))).join(",")}\r\n${IMPORT_FIELDS.map((f) => q(example[f])).join(",")}\r\n`;
}
