import { describe, expect, it } from "vitest";
import { DEFAULT_IMPORT_COLUMNS } from "../../shared/model";
import { mapColumns, parseKind, parseTable, parseTerm, planImport, templateCsv } from "../src/lib/table";

const none = { customers: [], licenses: [] };

describe("parseTable", () => {
  it("reads cells pasted from Excel (tabs)", () => {
    expect(parseTable("a\tb\tc\n1\t2\t3\n")).toEqual([["a", "b", "c"], ["1", "2", "3"]]);
  });
  it("reads CSV with commas or semicolons, quotes and doubled quotes, any line ending", () => {
    expect(parseTable('a,b\r\n"x, y","say ""hi"""\r\n')).toEqual([["a", "b"], ["x, y", 'say "hi"']]);
    expect(parseTable("a;b\n1,5;2")).toEqual([["a", "b"], ["1,5", "2"]]);
  });
  it("keeps a multi-line quoted cell together and drops blank lines and the BOM", () => {
    expect(parseTable('﻿a,b\n\n"two\nlines",x\n,\n')).toEqual([["a", "b"], ["two\nlines", "x"]]);
  });
  it("returns nothing for empty input", () => {
    expect(parseTable("")).toEqual([]);
    expect(parseTable("  \n \n")).toEqual([]);
  });
});

describe("mapColumns", () => {
  it("matches the default titles", () => {
    const m = mapColumns(Object.values(DEFAULT_IMPORT_COLUMNS), DEFAULT_IMPORT_COLUMNS);
    expect(m.index.customer).toBe(0);
    expect(m.index.endDate).toBe(7);
    expect(m.unknown).toEqual([]);
  });
  it("recognises common alternative names without any setup", () => {
    const m = mapColumns(["STT", "Tên khách hàng", "Phần mềm", "Ngày kết thúc", "Giá", "Hợp đồng số"], DEFAULT_IMPORT_COLUMNS);
    expect(m.index).toMatchObject({ customer: 1, product: 2, endDate: 3, value: 4 });
    expect(m.unknown).toContain("STT");
  });
  it("prefers titles the owner configured", () => {
    const cols = { ...DEFAULT_IMPORT_COLUMNS, customer: "Đối tác, KH" };
    const m = mapColumns(["Công ty", "Đối tác", "Sản phẩm", "Ngày hết hạn"], cols);
    expect(m.index.customer).toBe(1);
  });
  it("does not use one column for two fields", () => {
    const m = mapColumns(["Ngày hết hạn"], DEFAULT_IMPORT_COLUMNS);
    expect(Object.values(m.index).filter((i) => i === 0)).toHaveLength(1);
  });
});

describe("small readers", () => {
  it("reads the kind from words", () => {
    expect(parseKind("Bảo hành")).toBe("WARRANTY");
    expect(parseKind("bảo trì hàng năm")).toBe("MAINTENANCE");
    expect(parseKind("Thuê bao")).toBe("SUBSCRIPTION");
    expect(parseKind("")).toBe("LICENSE");
    expect(parseKind("Bản quyền")).toBe("LICENSE");
  });
  it("reads the term in months or years", () => {
    expect(parseTerm("12")).toBe(12);
    expect(parseTerm("6 tháng")).toBe(6);
    expect(parseTerm("1 năm")).toBe(12);
    expect(parseTerm("2 years")).toBe(24);
    expect(parseTerm("0")).toBeUndefined();
    expect(parseTerm("abc")).toBeUndefined();
    expect(parseTerm("1000")).toBeUndefined();
  });
});

describe("planImport", () => {
  const head = "Khách hàng\tSản phẩm\tLoại\tNgày bắt đầu\tNgày hết hạn\tGiá trị\tSố điện thoại";
  it("turns rows into licences and finds the customers to create", () => {
    const plan = planImport(`${head}\nCông ty ABC\tKế toán\tLicense\t16/3/2026\t15/3/2027\t120.000.000\t0912345678\nCông ty ABC\tNhân sự\tBảo hành\t\t30/06/2027\t50tr\t\nXYZ\tERP\t\t\t01-12-2026\t\t`, DEFAULT_IMPORT_COLUMNS, none);
    expect(plan.missing).toEqual([]);
    expect(plan.rows.map((r) => r.status)).toEqual(["ok", "ok", "ok"]);
    expect(plan.rows[0]).toMatchObject({ customer: "Công ty ABC", product: "Kế toán", kind: "LICENSE", startDate: "2026-03-16", endDate: "2027-03-15", value: 120_000_000, phone: "0912345678" });
    expect(plan.rows[1]).toMatchObject({ kind: "WARRANTY", endDate: "2027-06-30", value: 50_000_000 });
    expect(plan.rows[2]).toMatchObject({ endDate: "2026-12-01", value: undefined });
    expect(plan.newCustomers).toEqual(["Công ty ABC", "XYZ"]);
  });
  it("explains each bad row in plain words and keeps the good ones", () => {
    const plan = planImport(`${head}\n\tKế toán\t\t\t15/3/2027\t\t\nABC\t\t\t\t15/3/2027\t\t\nABC\tERP\t\t\t31/02/2027\t\t\nABC\tERP\t\t\t\t\t\nABC\tCRM\t\t01/06/2027\t01/01/2027\t\t\nABC\tOK\t\t\t15/3/2027\tabc\t`, DEFAULT_IMPORT_COLUMNS, none);
    expect(plan.rows.map((r) => r.status)).toEqual(["error", "error", "error", "error", "error", "ok"]);
    expect(plan.rows[0].problems).toEqual(["Thiếu tên khách"]);
    expect(plan.rows[1].problems).toEqual(["Thiếu sản phẩm"]);
    expect(plan.rows[2].problems[0]).toContain("không đọc được");
    expect(plan.rows[3].problems).toEqual(["Thiếu ngày hết hạn"]);
    expect(plan.rows[4].problems).toEqual(["Ngày bắt đầu sau ngày hết hạn"]);
    expect(plan.rows[5].notes[0]).toContain("giá trị");
    expect(plan.rows[0].line).toBe(2);
  });
  it("works out the end date from the start date and term when it is missing", () => {
    const plan = planImport("Khách hàng\tSản phẩm\tNgày bắt đầu\tThời hạn (tháng)\nABC\tERP\t16/03/2026\t12", DEFAULT_IMPORT_COLUMNS, none);
    expect(plan.rows[0]).toMatchObject({ status: "ok", endDate: "2027-03-15" });
    expect(plan.rows[0].notes[0]).toContain("tính từ");
  });
  it("reports missing columns instead of guessing", () => {
    expect(planImport("Tên\tGhi chú\nABC\tx", DEFAULT_IMPORT_COLUMNS, none).missing).toEqual(["customer", "product", "endDate"]);
    expect(planImport("", DEFAULT_IMPORT_COLUMNS, none).rows).toEqual([]);
  });
  it("skips licences that already exist, in the data or earlier in the same paste", () => {
    const existing = { customers: [{ id: "c1", name: "Công ty ABC" }], licenses: [{ customerId: "c1", product: "Kế toán", endDate: "2027-03-15" }] };
    const plan = planImport(`${head}\ncong ty abc\tKế TOÁN\t\t\t15/3/2027\t\t\nCông ty ABC\tNhân sự\t\t\t15/3/2027\t\t\nCông ty ABC\tNhân sự\t\t\t15/3/2027\t\t\nMới\tX\t\t\t15/3/2027\t\t`, DEFAULT_IMPORT_COLUMNS, existing);
    expect(plan.rows.map((r) => r.status)).toEqual(["duplicate", "ok", "duplicate", "ok"]);
    expect(plan.newCustomers).toEqual(["Mới"]);
    expect(plan.matchedCustomers).toBe(1);
  });
  it("round-trips its own template", () => {
    const plan = planImport(templateCsv(), DEFAULT_IMPORT_COLUMNS, none);
    expect(plan.missing).toEqual([]);
    expect(plan.rows).toHaveLength(1);
    expect(plan.rows[0]).toMatchObject({ status: "ok", customer: "Công ty ABC", contact: "Anh Nam", product: "Phần mềm kế toán", endDate: "2027-03-15", termMonths: 12, value: 120_000_000, contractNo: "HD-001" });
    expect(templateCsv().startsWith("﻿Khách hàng,")).toBe(true);
  });
});
