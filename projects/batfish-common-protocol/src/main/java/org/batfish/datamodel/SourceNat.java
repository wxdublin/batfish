package org.batfish.datamodel;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;

public class SourceNat implements Serializable {

  private static final String PROP_ACL = "acl";

  private static final String PROP_POOL_IP_FIRST = "poolIpFirst";

  private static final String PROP_POOL_IP_LAST = "poolIpLast";

  /** */
  private static final long serialVersionUID = 1L;

  private IpAccessList _acl;

  private Ip _poolIpFirst;

  private Ip _poolIpLast;

  @JsonProperty(PROP_ACL)
  public IpAccessList getAcl() {
    return _acl;
  }

  @JsonProperty(PROP_POOL_IP_FIRST)
  public Ip getPoolIpFirst() {
    return _poolIpFirst;
  }

  @JsonProperty(PROP_POOL_IP_LAST)
  public Ip getPoolIpLast() {
    return _poolIpLast;
  }

  @JsonProperty(PROP_ACL)
  public void setAcl(IpAccessList acl) {
    _acl = acl;
  }

  @JsonProperty(PROP_POOL_IP_FIRST)
  public void setPoolIpFirst(Ip poolIpFirst) {
    _poolIpFirst = poolIpFirst;
  }

  @JsonProperty(PROP_POOL_IP_LAST)
  public void setPoolIpLast(Ip poolIpLast) {
    _poolIpLast = poolIpLast;
  }
}
