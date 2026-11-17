
package com.socialhub.postapproval.service.interfaces;

import com.socialhub.postapproval.dto.PostApprovalDto;

import java.util.List;
import java.util.UUID;

public interface PostApprovalService {

    // Gửi bài viết vào danh sách chờ duyệt
    PostApprovalDto.Status submit(UUID postId);

    // Phê duyệt bài viết
    PostApprovalDto.Status approve(UUID postId);

    // Từ chối bài viết
    PostApprovalDto.Status reject(
            UUID postId,
            PostApprovalDto.Reject request
    );

    // Xem trạng thái phê duyệt hiện tại
    PostApprovalDto.Status getStatus(UUID postId);

    // Xem lịch sử duyệt bài
    List<PostApprovalDto.View> history(UUID postId);
}
