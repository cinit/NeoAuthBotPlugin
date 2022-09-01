package cc.ioctl.neoauth3bot.res

import java.util.*

interface Resources {

    val btn_text_change_quiz: String
    val btn_text_reset: String
    val btn_text_submit: String
    val btn_text_cancel: String

    val auth_instruction_part1: String
    val auth_instruction_part2: String
    val auth_instruction_part3: String
    val auth_ins_fail_on_timeout: String
    val auth_change_quiz_chances_left_part1: String
    val auth_change_quiz_chances_left_part2: String
    val auth_hint_all_region_count_part1: String
    val auth_hint_all_region_count_part2: String
    val auth_current_selected_regions: String
    val auth_none_selected: String

    val msg_text_auth_pass_va1: String
    val msg_text_approve_success: String
    val msg_text_error_denied_by_other_admin: String
    val msg_text_join_auth_required_notice_va2: String
    val msg_text_too_many_requests: String
    val msg_text_loading: String
    val msg_text_command_use_in_group_only: String
    val msg_text_command_use_in_private_chat_only: String
    val msg_text_no_auth_required: String
    val msg_text_approved_manually_by_admin_va1: String
    val msg_text_dismissed_manually_by_admin_va1: String
    val msg_text_banned_manually_by_admin_va1: String

    val cb_query_auth_session_not_found: String
    val cb_query_auth_fail_retry: String
    val cb_query_auth_pass: String
    val cb_query_selected_va1: String
    val cb_query_unselected_va1: String
    val cb_query_reset_region: String
    val cb_query_change_quiz_wip: String

    val help_info_category_auth: String
    val help_info_category_auth_description: String
    val help_info_category_test: String
    val help_info_category_test_description: String
    val help_info_category_other: String
    val help_info_category_other_description: String
    val help_info_require_at_in_group_va1: String

    val help_about_desc1_part1: String
    val help_about_desc1_part2: String
    val help_about_desc1_part3: String
    val help_about_desc2_part1: String
    val help_about_desc2_part2: String
    val help_about_desc3: String
    val help_about_discussion_group_link_va1: String

    val btn_text_verify_anony_identity: String
    val msg_text_anonymous_admin_identity_verification_required: String
    val cb_query_admin_permission_required: String
    val cb_query_nothing_to_do_with_you: String

    fun format(fm: String, vararg args: Any): String {
        return String.format(Locale.ROOT, fm, *args)
    }

}
