package cc.ioctl.neoauth3bot.res

import cc.ioctl.telebot.tdlib.obj.User

object ResImpl {

    val zhs = object : Resources {

        override val btn_text_change_quiz = "换一题"
        override val btn_text_reset = "重置"
        override val btn_text_submit = "提交"
        override val btn_text_cancel = "取消"

        override val auth_instruction_part1 = "欢迎 "
        override val auth_instruction_part2 = " 申请加群，本群已开启验证，请在 "
        override val auth_instruction_part3 = " 秒内选择图中所有包含手性碳的区域，并点击提交按钮。"
        override val auth_ins_fail_on_timeout = "超时或验证失败将会自动拒绝申请。"
        override val auth_change_quiz_chances_left_part1 = "您还可以更换 "
        override val auth_change_quiz_chances_left_part2 = " 次题目。"
        override val auth_hint_all_region_count_part1 = "提示：共有 "
        override val auth_hint_all_region_count_part2 = " 个区域。"
        override val auth_current_selected_regions = "当前已选择："
        override val auth_none_selected = "无"

        override val msg_text_auth_pass_va1 = "验证通过，用时 %d 秒。"
        override val msg_text_approve_success = "您应该已经是群成员了。"
        override val msg_text_error_denied_by_other_admin = "您的申请已被其他管理员拒绝。"
        override val msg_text_join_auth_required_notice_va2 = "欢迎 %1\$s 申请加入群组 %2\$s ，" +
                "本群已开启验证，请发送 /ccg 开始验证，完成验证后即可加入。"
        override val msg_text_too_many_requests = "您的操作过于频繁，请稍后再试。"
        override val msg_text_loading = "正在加载..."
        override val msg_text_no_auth_required = "您没有申请加入任何群，不需要验证。"
        override val msg_text_command_use_in_group_only = "请在群组中使用本命令。"
        override val msg_text_command_use_in_private_chat_only = "该命令只能在私聊中使用。"

        override val cb_query_auth_session_not_found = "太久远了，找不到了。"
        override val cb_query_auth_fail_retry = "验证失败，请重试。"
        override val cb_query_auth_pass = "验证通过"
        override val cb_query_selected_va1 = "选择了 %s"
        override val cb_query_unselected_va1 = "取消了 %s"
        override val cb_query_reset_region = "已重置"
        override val cb_query_change_quiz_wip = "暂不开放(请手动发送 /ccg 命令)"

        override val help_info_category_auth = "验证相关"
        override val help_info_category_auth_description = "/ccg 开始入群验证以及更换题目\n" +
                "/config 配置入群验证"
        override val help_info_category_test = "测试相关"
        override val help_info_category_test_description = "/cc1 仅用于测试"
        override val help_info_category_other = "其他"
        override val help_info_category_other_description = "/help 显示本信息\n" +
                "/uptime 测试机器人存活\n" +
                "/about 关于本机器人"
        override val help_info_require_at_in_group_va1 = "在群里使用命令时请在命令后加上 @机器人用户名, 如 %s"

        override val help_about_desc1_part1 = "使用 "
        override val help_about_desc1_part2 = " 框架开发, \n分子数据库来自 "
        override val help_about_desc1_part3 = " ."
        override val help_about_desc2_part1 = "你可以在 "
        override val help_about_desc2_part2 = " 获取本机器人的源代码。"
        override val help_about_desc3 =
            "如需在自己群使用，需要将群启用 成员需要批准才能加入(如为公开群，请先打开 只有群成员才能发消息)，" +
                    "或者创建一个需要批准才能加入的邀请链接，然后将机器人拉到群里设置为管理员，" +
                    "并给机器人 can_invite_users (必须, 添加成员/生成邀请链接) " +
                    "和 can_delete_messages (删除消息, 推荐, 但不是必须) 管理员权限(其他权限不需要)。\n" +
                    "目前仍在开发阶段, 十分不稳定, 提供 0% 的 SLA."

        override val btn_text_verify_anony_identity = "点此验证"
        override val msg_text_anonymous_admin_identity_verification_required =
            "您现在是以匿名管理员，请点击下方按钮验证身份。"
        override val cb_query_admin_permission_required = "需要管理员权限"
        override val cb_query_nothing_to_do_with_you = "与汝无瓜"
    }

    val eng = object : Resources {

        override val btn_text_change_quiz = "Change Quiz"
        override val btn_text_reset = "Reset"
        override val btn_text_submit = "Submit"
        override val btn_text_cancel = "Cancel"

        override val auth_instruction_part1 = "Welcome "
        override val auth_instruction_part2 = ". This group has anti-spam CAPTCHA enabled.\n" +
                "Please select all regions containing chiral carbon atoms (aka asymmetric carbon atoms) in "
        override val auth_instruction_part3 = " seconds to complete the CAPTCHA."
        override val auth_ins_fail_on_timeout = "Join request will be automatically approved after " +
                "the CAPTCHA is completed in time, vice versa."
        override val auth_change_quiz_chances_left_part1 = "You have "
        override val auth_change_quiz_chances_left_part2 = " chance(s) to change for a new CAPTCHA."
        override val auth_hint_all_region_count_part1 = "Hint: There should be "
        override val auth_hint_all_region_count_part2 = " region(s) in the answer."
        override val auth_current_selected_regions = "Selected: "
        override val auth_none_selected = "none"

        override val msg_text_auth_pass_va1 = "Authentication passed within %d seconds."
        override val msg_text_approve_success = "You should have been approved into the group."
        override val msg_text_error_denied_by_other_admin = "Your request was denied by an admin."
        override val msg_text_join_auth_required_notice_va2 = "Thank you for your group join request.\n" +
                "Group %2\$s has anti-spam CAPTCHA enabled. Please send /ccg to complete the CAPTCHA."
        override val msg_text_too_many_requests = "Too many requests. Please try again later."
        override val msg_text_loading = "Loading..."
        override val msg_text_no_auth_required =
            "No authentication required because you did not request to join a group."
        override val msg_text_command_use_in_group_only = "Please use this command in the group."
        override val msg_text_command_use_in_private_chat_only = "This command can only be used in private chat."

        override val cb_query_auth_session_not_found = "Session not found or lost. (Try to restart authentication.)"
        override val cb_query_auth_fail_retry = "Wrong answer. Please check your answer and try again."
        override val cb_query_auth_pass = "Authentication succeeded."
        override val cb_query_selected_va1 = "Select %s"
        override val cb_query_unselected_va1 = "Unselect %s"
        override val cb_query_reset_region = "Reset"
        override val cb_query_change_quiz_wip = "Not implemented. Send /ccg manually."

        override val help_info_category_auth = "Authentication"
        override val help_info_category_auth_description = "/ccg Start authentication or change CAPTCHA\n" +
                "/config Edit configuration"
        override val help_info_category_test = "Test"
        override val help_info_category_test_description = "/cc1 For test use only."
        override val help_info_category_other = "Other"
        override val help_info_category_other_description = "/help Show this message\n" +
                "/uptime Test whether this bot is alive\n" +
                "/about About this bot"
        override val help_info_require_at_in_group_va1 =
            "A @-mention for this bot is required to use commands in group. e.g. %s"

        override val help_about_desc1_part1 = "This bot is developed with "
        override val help_about_desc1_part2 = ". \nThe molecular database is from "
        override val help_about_desc1_part3 = "."
        override val help_about_desc2_part1 = "You may obtain the source code of this bot at "
        override val help_about_desc2_part2 = ""
        override val help_about_desc3 = "If you want to use this bot in your group, you need to enable " +
                "\"approve new member\" for your group (which requires owner permission) or create a " +
                "invite link with \"approve new member\", and then set the bot as an administrator " +
                "with can_invite_users (required) and can_delete_messages (recommended, but not necessary) " +
                "permissions (other permissions are not required).\n" +
                "Currently, the bot is still under development, providing 0% SLA."

        override val btn_text_verify_anony_identity = "Click To Verify"
        override val msg_text_anonymous_admin_identity_verification_required = "You are now as an anonymous admin.\n" +
                "Please click the button below to verify your identity."
        override val cb_query_admin_permission_required = "Admin permission required."
        override val cb_query_nothing_to_do_with_you = "It's not related to you."
    }

    fun getResourceForLanguage(lang: String): Resources {
        return when {
            lang.startsWith("zh") -> zhs
            else -> eng
        }
    }

    fun getResourceForUser(user: User): Resources {
        return getResourceForLanguage(user.languageCode ?: "")
    }

}
