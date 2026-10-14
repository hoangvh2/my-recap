# Quy ước của project

## Xác nhận trước khi thực hiện thao tác phá huỷ

Mọi thao tác **xoá dữ liệu, bỏ liên kết/ngừng chia sẻ, đăng xuất** (và các việc tương tự, không hoàn tác được hoặc làm mất dữ liệu) trên Android và web **phải hiện dialog xác nhận** trước khi chạy. Không bao giờ thực hiện ngay từ một cử chỉ (vuốt, nhấn giữ), phím tắt hay nút bấm đơn lẻ.

- Dialog nêu rõ việc sắp làm và đối tượng (ví dụ "Xoá mục này?" kèm tiêu đề). Nút huỷ ở bên trái, nút hành động bên phải, màu đỏ (destructive).
- Vẫn giữ Hoàn tác (Undo) sau khi xác nhận nếu có thể; xác nhận không thay cho Undo và ngược lại.
- Cử chỉ vuốt để xoá: hàng bật về chỗ cũ, dialog quyết định; danh sách chỉ đổi sau khi người dùng xác nhận.
- Hành động TalkBack/bàn phím/popover tương đương cũng phải qua cùng dialog.
- Android dùng `ConfirmDialog(... destructive = true)` (`ui/Components.kt`); web dùng `confirmDialog({ ..., danger: true })` (`ui/common.tsx`), đăng xuất dùng `signOutAsk()`.
- Tính năng mới có thao tác phá huỷ phải kèm test (e2e web, instrumented Android) kiểm tra cả "Không" (dữ liệu còn nguyên) và "Xoá".
