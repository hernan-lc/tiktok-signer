use ttl_live_discovery::{
    interpret_live_search_page, live_search_url_with_context, DiscoveryError, LiveSearchCursor,
};
fn page(cursor: usize, request_id: &str) -> String {
    serde_json::json!({"status_code":0,"data":[{"live_info":{"raw_data":serde_json::json!({"id_str":"7300000000000000001","status":2,"owner":{"display_id":"fixture"}}).to_string()}}],"has_more":1,"cursor":cursor,"log_pb":{"impr_id":request_id}}).to_string()
}
#[test]
fn real_search_continuation_keeps_the_initial_context_and_advances_the_server_cursor() {
    let first = interpret_live_search_page(&page(20, "first"), None).unwrap();
    assert_eq!(first.rooms.len(), 1);
    let cursor = first.next.unwrap();
    let second = interpret_live_search_page(&page(40, "second"), Some(&cursor)).unwrap();
    assert_eq!(
        second.next.unwrap(),
        LiveSearchCursor {
            offset: 40,
            search_id: "first".into()
        }
    );
    let url = live_search_url_with_context("a b", 20, "id&extra=1");
    assert!(url.contains("keyword=a%20b"));
    assert!(url.contains("offset=20"));
    assert!(url.contains("search_id=id%26extra%3D1"));
}
#[test]
fn terminal_continuations_can_omit_data_but_refusals_and_unstructured_bodies_cannot() {
    let cursor = LiveSearchCursor {
        offset: 20,
        search_id: "first".into(),
    };
    let end = r#"{"status_code":0,"has_more":0,"cursor":40}"#;
    let result = interpret_live_search_page(end, Some(&cursor)).unwrap();
    assert!(result.rooms.is_empty() && result.next.is_none());
    assert!(interpret_live_search_page(end, None).is_err());
    assert!(interpret_live_search_page("{}", Some(&cursor)).is_err());
    assert!(matches!(
        interpret_live_search_page(r#"{"status_code":102,"has_more":0}"#, Some(&cursor)),
        Err(DiscoveryError::Refused(_))
    ));
}
#[test]
fn repeated_cursor_and_missing_context_are_errors_not_infinite_pagination_or_false_exhaustion() {
    let cursor = LiveSearchCursor {
        offset: 20,
        search_id: "first".into(),
    };
    assert!(interpret_live_search_page(&page(20, "second"), Some(&cursor)).is_err());
    assert!(interpret_live_search_page(
        r#"{"status_code":0,"data":[],"has_more":1,"cursor":20}"#,
        None
    )
    .is_err());
}
